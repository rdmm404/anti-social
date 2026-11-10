// Package web is the settings page: where the account stands, its limits, the last week and
// its subscriptions. Signing in sets a cookie holding the device token.
package web

import (
	"embed"
	"fmt"
	"html/template"
	"log/slog"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/rdmm404/anti-social/server/internal/accounts"
	"github.com/rdmm404/anti-social/server/internal/apperr"
	"github.com/rdmm404/anti-social/server/internal/budget"
	"github.com/rdmm404/anti-social/server/internal/httpapi"
	"github.com/rdmm404/anti-social/server/internal/library"
)

//go:embed templates/*.html
var templateFiles embed.FS

//go:embed static
var staticFiles embed.FS

var pages = template.Must(template.New("").Funcs(template.FuncMap{
	"minutes": minutesText,
	"clock":   func(t time.Time) string { return t.Local().Format("3:04 pm") },
	"when":    func(t time.Time) string { return t.Local().Format("Mon Jan 2, 3:04 pm") },
	"hours":   func(d time.Duration) int { return int(d.Hours()) },
	"label":   settingLabel,
	"low":     func(key string) int { l, _ := budget.Bounds(key); return l },
	"high":    func(key string) int { _, h := budget.Bounds(key); return h },
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
	case budget.DailyMinutes:
		return "Daily budget"
	case budget.SessionGapMinutes:
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
	Status        budget.Status
	Settings      budget.SettingsView
	SettingNames  []string
	Days          []budget.DayTotal
	Subscriptions []library.Subscription
	Notice        string
	Problem       string
}

type Page struct {
	accounts *accounts.Service
	budget   *budget.Service
	library  *library.Service
}

func New(a *accounts.Service, b *budget.Service, l *library.Service) *Page {
	return &Page{accounts: a, budget: b, library: l}
}

func (p *Page) Register(mux *http.ServeMux) {
	// The page's forms are same-origin only, whatever the cookie would allow.
	guard := http.NewCrossOriginProtection()
	form := func(pattern string, h http.HandlerFunc) { mux.Handle(pattern, guard.Handler(h)) }

	mux.Handle("GET /static/", http.FileServerFS(staticFiles))
	mux.HandleFunc("GET /{$}", p.page)
	form("POST /sign-in", p.signIn)
	form("POST /sign-out", p.signOut)
	form("POST /settings", p.signedIn(p.saveSettings))
	form("POST /settings/pending/{id}/cancel", p.signedIn(p.cancel))
}

func secure(r *http.Request) bool {
	return r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https"
}

func setTokenCookie(w http.ResponseWriter, r *http.Request, value string, maxAge int) {
	http.SetCookie(w, &http.Cookie{
		Name:     httpapi.CookieName,
		Value:    value,
		Path:     "/",
		MaxAge:   maxAge,
		HttpOnly: true,
		Secure:   secure(r),
		SameSite: http.SameSiteStrictMode,
	})
}

// signedIn runs next with the account, or sends a signed-out browser back to sign in.
func (p *Page) signedIn(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, err := p.accounts.Authenticate(r.Context(), httpapi.Token(r))
		if apperr.Is(err, apperr.Unauthorized) {
			http.Redirect(w, r, "/", http.StatusSeeOther)
			return
		}
		if err != nil {
			httpapi.Fail(w, r, err)
			return
		}
		next(w, r.WithContext(httpapi.WithAccount(r.Context(), id)))
	}
}

func back(w http.ResponseWriter, r *http.Request, kind, message string) {
	http.Redirect(w, r, "/?"+url.Values{kind: {message}}.Encode(), http.StatusSeeOther)
}

func (p *Page) page(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	account, err := p.accounts.Authenticate(r.Context(), httpapi.Token(r))
	if apperr.Is(err, apperr.Unauthorized) {
		render(w, "sign-in.html", map[string]string{"Problem": r.URL.Query().Get("problem")})
		return
	}
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	data := pageData{SettingNames: budget.SettingNames, Notice: r.URL.Query().Get("notice"), Problem: r.URL.Query().Get("problem")}
	ctx := r.Context()
	if data.Status, err = p.budget.Status(ctx, account); err == nil {
		if data.Settings, err = p.budget.Settings(ctx, account); err == nil {
			var h budget.History
			if h, err = p.budget.History(ctx, account, 7); err == nil {
				data.Days = h.Days
				data.Subscriptions, err = p.library.Subscriptions(ctx, account)
			}
		}
	}
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	render(w, "settings.html", data)
}

func render(w http.ResponseWriter, name string, data any) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := pages.ExecuteTemplate(w, name, data); err != nil {
		slog.Error("render", "page", name, "err", err)
	}
}

func (p *Page) signIn(w http.ResponseWriter, r *http.Request) {
	given := r.PostFormValue("token")
	if _, err := p.accounts.Authenticate(r.Context(), given); err != nil {
		back(w, r, "problem", "That token doesn't match this server's.")
		return
	}
	setTokenCookie(w, r, given, 400*24*3600)
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (p *Page) signOut(w http.ResponseWriter, r *http.Request) {
	setTokenCookie(w, r, "", -1)
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

func (p *Page) saveSettings(w http.ResponseWriter, r *http.Request) {
	changes := map[string]int{}
	for _, key := range budget.SettingNames {
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
	result, err := p.budget.ChangeSettings(r.Context(), httpapi.Account(r), changes)
	if e, ok := apperr.As(err); ok {
		back(w, r, "problem", e.Message)
		return
	}
	if err != nil {
		httpapi.Fail(w, r, err)
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

func (p *Page) cancel(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil {
		back(w, r, "problem", "That change no longer exists.")
		return
	}
	if err := p.budget.CancelChange(r.Context(), httpapi.Account(r), id); err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	back(w, r, "notice", "Change cancelled.")
}
