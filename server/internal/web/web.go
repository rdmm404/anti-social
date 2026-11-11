// Package web is the settings page: where the account stands, its limits, the last week, its
// subscriptions and its devices, and where a new device's pairing code is approved.
//
// Signing in with an existing token makes this browser a device of its own, with its own
// token in the cookie, so signing out or removing it here revokes just that browser.
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
	"strings"
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
	"date":    func(t time.Time) string { return t.Local().Format("Jan 2, 2006") },
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
	Devices       []accounts.DeviceInfo
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
	mux.HandleFunc("GET /pair", p.pairPage)
	form("POST /sign-in", p.signIn)
	form("POST /sign-out", p.signOut)
	form("POST /pair", p.signedIn(p.pair))
	form("POST /devices/{id}/remove", p.signedIn(p.removeDevice))
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
		// Lax, not Strict: the phone app opens this page from outside the browser, and Chrome
		// keeps a Strict cookie back on a page another app opens, so it asked to sign in each time.
		SameSite: http.SameSiteLaxMode,
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

// signInPage asks for a token, then returns to next.
func signInPage(w http.ResponseWriter, problem, next string) {
	render(w, "sign-in.html", map[string]string{"Problem": problem, "Next": next})
}

// afterSignIn is where signing in returns to: only this site's pairing page, or home.
func afterSignIn(next string) string {
	if strings.HasPrefix(next, "/pair?") || next == "/pair" {
		return next
	}
	return "/"
}

func (p *Page) page(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	account, err := p.accounts.Authenticate(r.Context(), httpapi.Token(r))
	if apperr.Is(err, apperr.Unauthorized) {
		signInPage(w, r.URL.Query().Get("problem"), "")
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
				if data.Subscriptions, err = p.library.Subscriptions(ctx, account); err == nil {
					data.Devices, err = p.accounts.Devices(ctx, account)
				}
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
	next := afterSignIn(r.PostFormValue("next"))
	account, err := p.accounts.Authenticate(r.Context(), r.PostFormValue("token"))
	if apperr.Is(err, apperr.Unauthorized) {
		signInPage(w, "That token doesn't match this server's.", next)
		return
	}
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	token, err := p.accounts.AddDevice(r.Context(), account, "Browser")
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	setTokenCookie(w, r, token, 400*24*3600)
	http.Redirect(w, r, next, http.StatusSeeOther)
}

func (p *Page) signOut(w http.ResponseWriter, r *http.Request) {
	if err := p.accounts.SignOut(r.Context(), httpapi.Token(r)); err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	setTokenCookie(w, r, "", -1)
	http.Redirect(w, r, "/", http.StatusSeeOther)
}

// pairPage approves the device showing a code. The TV's QR code lands here with the code filled
// in; signed out, it asks for a token first and comes back.
func (p *Page) pairPage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	_, err := p.accounts.Authenticate(r.Context(), httpapi.Token(r))
	if apperr.Is(err, apperr.Unauthorized) {
		signInPage(w, "", r.URL.RequestURI())
		return
	}
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	q := r.URL.Query()
	render(w, "pair.html", map[string]string{"Code": q.Get("code"), "Problem": q.Get("problem")})
}

func (p *Page) pair(w http.ResponseWriter, r *http.Request) {
	code := r.PostFormValue("code")
	name, err := p.accounts.ApprovePairing(r.Context(), httpapi.Account(r), code)
	if e, ok := apperr.As(err); ok {
		http.Redirect(w, r, "/pair?"+url.Values{"code": {code}, "problem": {e.Message}}.Encode(), http.StatusSeeOther)
		return
	}
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	back(w, r, "notice", name+" is paired. It signs in within a few seconds.")
}

func (p *Page) removeDevice(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err == nil {
		err = p.accounts.RemoveDevice(r.Context(), httpapi.Account(r), id)
	}
	if err != nil && !apperr.Is(err, apperr.NotFound) {
		httpapi.Fail(w, r, err)
		return
	}
	// Removing this browser signs it out; the next page load asks for a token.
	back(w, r, "notice", "Device removed. It's signed out.")
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
