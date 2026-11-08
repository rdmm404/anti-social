package api

import (
	"embed"
	"errors"
	"fmt"
	"html/template"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/rdmm404/anti-social/server/internal/accounts"
	"github.com/rdmm404/anti-social/server/internal/ledger"
	"github.com/rdmm404/anti-social/server/internal/library"
)

//go:embed templates/*.html
var templateFiles embed.FS

//go:embed static
var staticFiles embed.FS

var pages = template.Must(template.New("").Funcs(template.FuncMap{
	"minutes": minutesText,
	"clock":   func(unix float64) string { return time.UnixMilli(int64(unix * 1000)).Format("3:04 pm") },
	"when":    func(t time.Time) string { return t.Local().Format("Mon Jan 2, 3:04 pm") },
	"label":   settingLabel,
	"low":     func(key string) int { l, _ := ledger.Bounds(key); return l },
	"high":    func(key string) int { _, h := ledger.Bounds(key); return h },
	"percent": percent,
	"weekday": weekday,
}).ParseFS(templateFiles, "templates/*.html"))

// 90 -> 2m, 3660 -> 1h 1m. Rounds up, like the apps.
func minutesText(seconds int64) string {
	m := int64(math.Ceil(float64(seconds) / 60))
	if m < 60 {
		return fmt.Sprintf("%dm", m)
	}
	if m%60 == 0 {
		return fmt.Sprintf("%dh", m/60)
	}
	return fmt.Sprintf("%dh %dm", m/60, m%60)
}

func settingLabel(key string) string {
	switch key {
	case ledger.DailyMinutes:
		return "Daily budget"
	case ledger.SessionGapMinutes:
		return "Gap between sessions"
	}
	return key
}

func percent(part, whole int64) int {
	if whole <= 0 {
		return 100
	}
	return int(min(100, max(0, part*100/whole)))
}

func weekday(day string) string {
	t, err := time.Parse(time.DateOnly, day)
	if err != nil {
		return day
	}
	return t.Format("Mon Jan 2")
}

type pageData struct {
	Status        ledger.Status
	Settings      ledger.SettingsView
	SettingNames  []string
	Days          []ledger.DayTotal
	Subscriptions []library.Subscription
	Notice        string
	Problem       string
}

func (s *Server) pageRoutes(mux *http.ServeMux) {
	// The page's forms are same-origin only, whatever the cookie would allow.
	guard := http.NewCrossOriginProtection()
	form := func(pattern string, h http.HandlerFunc) { mux.Handle(pattern, guard.Handler(h)) }

	mux.Handle("GET /static/", http.FileServerFS(staticFiles))
	mux.HandleFunc("GET /{$}", s.page)
	form("POST /sign-in", s.signIn)
	form("POST /sign-out", s.signOut)
	form("POST /settings", s.pageAuthenticated(s.saveSettings))
	form("POST /settings/pending/{id}/cancel", s.pageAuthenticated(s.cancelFromPage))
}

func secure(r *http.Request) bool {
	return r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https"
}

func setTokenCookie(w http.ResponseWriter, r *http.Request, value string, maxAge int) {
	http.SetCookie(w, &http.Cookie{
		Name:     cookieName,
		Value:    value,
		Path:     "/",
		MaxAge:   maxAge,
		HttpOnly: true,
		Secure:   secure(r),
		SameSite: http.SameSiteStrictMode,
	})
}

// pageAuthenticated is authenticated for forms: a signed-out browser goes back to sign in.
func (s *Server) pageAuthenticated(next func(http.ResponseWriter, *http.Request)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if _, err := s.accounts.Authenticate(r.Context(), token(r)); errors.Is(err, accounts.ErrUnknownToken) {
			http.Redirect(w, r, "/", http.StatusSeeOther)
			return
		}
		s.authenticated(next)(w, r)
	}
}

func back(w http.ResponseWriter, r *http.Request, kind, message string) {
	http.Redirect(w, r, "/?"+url.Values{kind: {message}}.Encode(), http.StatusSeeOther)
}

func (s *Server) page(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	account, err := s.accounts.Authenticate(r.Context(), token(r))
	if errors.Is(err, accounts.ErrUnknownToken) {
		s.render(w, "sign-in.html", map[string]string{"Problem": r.URL.Query().Get("problem")})
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	data := pageData{SettingNames: ledger.SettingNames, Notice: r.URL.Query().Get("notice"), Problem: r.URL.Query().Get("problem")}
	ctx := r.Context()
	if data.Status, err = s.ledger.Status(ctx, account); err == nil {
		if data.Settings, err = s.ledger.Settings(ctx, account); err == nil {
			var h ledger.History
			if h, err = s.ledger.History(ctx, account, 7); err == nil {
				data.Days = h.Days
				data.Subscriptions, err = s.library.Subscriptions(ctx, account)
			}
		}
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.render(w, "settings.html", data)
}

func (s *Server) render(w http.ResponseWriter, name string, data any) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := pages.ExecuteTemplate(w, name, data); err != nil {
		s.log.Error("render", "page", name, "err", err)
	}
}

func (s *Server) signIn(w http.ResponseWriter, r *http.Request) {
	given := r.PostFormValue("token")
	if _, err := s.accounts.Authenticate(r.Context(), given); err != nil {
		back(w, r, "problem", "That token doesn't match this server's.")
		return
	}
	setTokenCookie(w, r, given, 400*24*3600)
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (s *Server) signOut(w http.ResponseWriter, r *http.Request) {
	setTokenCookie(w, r, "", -1)
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (s *Server) saveSettings(w http.ResponseWriter, r *http.Request) {
	changes := map[string]int{}
	for _, key := range ledger.SettingNames {
		raw := r.PostFormValue(key)
		if raw == "" {
			continue
		}
		v, err := strconv.Atoi(raw)
		if err != nil {
			back(w, r, "problem", settingLabel(key)+" must be a whole number of minutes.")
			return
		}
		changes[key] = v
	}
	result, err := s.ledger.ChangeSettings(r.Context(), accountOf(r), changes)
	var refusal *ledger.Refusal
	if errors.As(err, &refusal) {
		back(w, r, "problem", refusal.Message)
		return
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	switch {
	case len(result.Queued) > 0 && len(result.Applied) > 0:
		back(w, r, "notice", "Tighter limits apply now. Looser ones wait out the cooling-off.")
	case len(result.Queued) > 0:
		back(w, r, "notice", "Saved. Looser limits wait out the cooling-off before they apply.")
	case len(result.Applied) > 0:
		back(w, r, "notice", "Saved. Tighter limits apply now.")
	default:
		back(w, r, "notice", "Nothing changed.")
	}
}

func (s *Server) cancelFromPage(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		back(w, r, "problem", "That change no longer exists.")
		return
	}
	if err := s.ledger.CancelChange(r.Context(), accountOf(r), id); err != nil {
		s.fail(w, r, err)
		return
	}
	back(w, r, "notice", "Change cancelled.")
}
