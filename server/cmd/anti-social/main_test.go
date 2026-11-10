package main

// The routes end to end over HTTP: the wire format, status codes, auth and the settings page.
// They run on the in-memory adapters; the rules themselves are tested in each domain.

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/rdmm404/anti-social/server/internal/accounts"
	accountsmem "github.com/rdmm404/anti-social/server/internal/accounts/memory"
	"github.com/rdmm404/anti-social/server/internal/budget"
	budgetmem "github.com/rdmm404/anti-social/server/internal/budget/memory"
	"github.com/rdmm404/anti-social/server/internal/library"
	librarymem "github.com/rdmm404/anti-social/server/internal/library/memory"
)

const token = "0123456789abcdef0123456789abcdef"

type client struct {
	t      *testing.T
	server *httptest.Server
	token  string
}

func setup(t *testing.T) *client {
	t.Helper()
	t.Parallel()
	accts := accounts.NewService(accountsmem.New(), nil)
	account, err := accts.EnsureOwner(context.Background(), map[string]string{"test": token})
	if err != nil {
		t.Fatal(err)
	}
	budgets := budgetmem.New()
	budgets.AddAccount(account)
	server := httptest.NewServer(handler(services{
		accounts: accts,
		budget: budget.NewService(budgets, budget.Config{
			Location:      time.UTC,
			DayStartHour:  4,
			CoolingOff:    72 * time.Hour,
			CheckinExpiry: time.Minute,
			CheckinEvery:  15 * time.Second,
			Seeds:         map[string]int{budget.DailyMinutes: 10, budget.SessionGapMinutes: 20},
		}, nil),
		library: library.NewService(librarymem.New(), nil),
	}))
	t.Cleanup(server.Close)
	return &client{t, server, token}
}

// do sends a request with the device token and returns the status and decoded body.
func (c *client) do(method, path string, body any) (int, map[string]any) {
	c.t.Helper()
	var reader io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		reader = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, c.server.URL+path, reader)
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer res.Body.Close()
	var decoded map[string]any
	json.NewDecoder(res.Body).Decode(&decoded)
	return res.StatusCode, decoded
}

func (c *client) expect(method, path string, body any, want int) map[string]any {
	c.t.Helper()
	code, decoded := c.do(method, path, body)
	if code != want {
		c.t.Fatalf("%s %s: got %d %v, want %d", method, path, code, decoded, want)
	}
	return decoded
}

func TestEveryEndpointNeedsAToken(t *testing.T) {
	c := setup(t)
	c.token = ""
	for _, path := range []string{"/api/status", "/api/subscriptions", "/api/playlists", "/api/settings", "/api/history"} {
		body := c.expect("GET", path, nil, 401)
		if errorCode(body) != "unauthorized" {
			t.Fatalf("%s: %v", path, body)
		}
	}
	c.expect("POST", "/api/checkins", map[string]any{"video_id": "abcdefghijk"}, 401)
	c.token = strings.Repeat("x", 32)
	c.expect("GET", "/api/status", nil, 401)
}

func TestCheckinsOverHTTP(t *testing.T) {
	c := setup(t)
	video := map[string]any{"video_id": "abcdefghijk", "title": "T", "channel": "C", "watched": [][]float64{}}

	refused := c.expect("POST", "/api/checkins", video, 200)
	if refused["allowed"] != false || refused["reason"] != "no_session" {
		t.Fatalf("before a session: %v", refused)
	}
	c.expect("POST", "/api/session", map[string]int{"videos": 9}, 400)
	c.expect("POST", "/api/session", map[string]int{"videos": 1}, 200)
	if out := c.expect("POST", "/api/session", map[string]int{"videos": 1}, 409); errorCode(out) != "refused" {
		t.Fatalf("a second session: %v", out)
	}
	status := c.expect("GET", "/api/status", nil, 200)
	if status["state"] != "open" {
		t.Fatalf("after starting: %v", status)
	}

	allowed := c.expect("POST", "/api/checkins", video, 200)
	if allowed["allowed"] != true || allowed["reason"] != nil {
		t.Fatalf("in a session: %v", allowed)
	}
	if allowed["expires_at"].(float64) <= allowed["status"].(map[string]any)["now"].(float64) {
		t.Fatalf("expiry isn't in the future: %v", allowed)
	}

	video["watched"] = [][]float64{{0, 700}}
	out := c.expect("POST", "/api/checkins", video, 200)
	if out["allowed"] != false || out["reason"] != "out_of_time" {
		t.Fatalf("past the budget: %v", out)
	}
	if got := c.expect("GET", "/api/status", nil, 200)["used_seconds"]; got != 700.0 {
		t.Fatalf("used %v, want 700", got)
	}
	history := c.expect("GET", "/api/history/videos?q=t", nil, 200)["videos"].([]any)
	if len(history) != 1 || history[0].(map[string]any)["title"] != "T" {
		t.Fatalf("history: %v", history)
	}
}

func TestCheckinsRejectNonsense(t *testing.T) {
	c := setup(t)
	for _, body := range []map[string]any{
		{"video_id": "../etc", "watched": [][]float64{}},
		{"video_id": "abcdefghijk", "watched": [][]float64{{10, 5}}},
		{"video_id": "abcdefghijk", "watched": [][]float64{{-1, 5}}},
		{"video_id": "abcdefghijk", "watched": [][]float64{{0, 1e9}}},
	} {
		c.expect("POST", "/api/checkins", body, 400)
	}
	req, _ := http.NewRequest("POST", c.server.URL+"/api/checkins", strings.NewReader("{"))
	req.Header.Set("Authorization", "Bearer "+token)
	res, _ := http.DefaultClient.Do(req)
	if res.StatusCode != 400 {
		t.Fatalf("broken JSON: %d", res.StatusCode)
	}
}

func TestSubscriptions(t *testing.T) {
	c := setup(t)
	c.expect("POST", "/api/subscriptions", map[string]string{"channel_id": "UCabcdefghijklmnopqrstuv", "name": "zed"}, 204)
	c.expect("POST", "/api/subscriptions", map[string]string{"channel_id": "@someone", "name": "Alpha"}, 204)
	c.expect("POST", "/api/subscriptions", map[string]string{"channel_id": "not a channel", "name": "x"}, 400)
	subs := c.expect("GET", "/api/subscriptions", nil, 200)["subscriptions"].([]any)
	if len(subs) != 2 || subs[0].(map[string]any)["name"] != "Alpha" {
		t.Fatalf("subscriptions: %v", subs)
	}
	c.expect("DELETE", "/api/subscriptions/@someone", nil, 204)
	if subs := c.expect("GET", "/api/subscriptions", nil, 200)["subscriptions"].([]any); len(subs) != 1 {
		t.Fatalf("after unsubscribing: %v", subs)
	}
}

func TestPlaylists(t *testing.T) {
	c := setup(t)
	c.expect("POST", "/api/playlists", map[string]string{"name": " "}, 400)
	id := int(c.expect("POST", "/api/playlists", map[string]string{"name": " Later "}, 200)["id"].(float64))
	path := "/api/playlists/" + itoa(id)
	c.expect("POST", path+"/items", map[string]any{"id": "bbbbbbbbbbb", "title": "B"}, 204)
	c.expect("POST", path+"/items", map[string]any{"id": "aaaaaaaaaaa", "title": "A", "channel": "c", "duration": 9}, 204)
	c.expect("POST", path+"/items", map[string]any{"id": "bbbbbbbbbbb", "title": "B again"}, 204)
	c.expect("POST", path+"/items", map[string]any{"id": "../bad", "title": "x"}, 400)
	c.expect("POST", "/api/playlists/999/items", map[string]any{"id": "ccccccccccc", "title": "C"}, 404)

	p := c.expect("GET", path, nil, 200)
	items := p["items"].([]any)
	if p["name"] != "Later" || len(items) != 2 || items[0].(map[string]any)["title"] != "B" {
		t.Fatalf("playlist: %v", p)
	}
	lists := c.expect("GET", "/api/playlists", nil, 200)["playlists"].([]any)
	if lists[0].(map[string]any)["count"] != 2.0 {
		t.Fatalf("playlists: %v", lists)
	}
	c.expect("DELETE", path+"/items/bbbbbbbbbbb", nil, 204)
	c.expect("DELETE", path, nil, 204)
	c.expect("GET", path, nil, 404)
	c.expect("GET", "/api/playlists/nope", nil, 404)
}

func TestSettingsOverHTTP(t *testing.T) {
	c := setup(t)
	result := c.expect("POST", "/api/settings", map[string]any{"changes": map[string]int{"daily_minutes": 100}}, 200)
	if q := result["queued"].([]any); len(q) != 1 {
		t.Fatalf("change: %v", result)
	}
	view := c.expect("GET", "/api/settings", nil, 200)
	pending := view["pending"].([]any)
	if len(pending) != 1 || view["cooling_off_hours"] != 72.0 {
		t.Fatalf("settings: %v", view)
	}
	c.expect("DELETE", "/api/settings/pending/"+itoa(int(pending[0].(map[string]any)["id"].(float64))), nil, 204)
	c.expect("POST", "/api/settings", map[string]any{"changes": map[string]int{"cooling_off_hours": 0}}, 400)
	c.expect("GET", "/api/history/videos?start='; drop", nil, 400)
}

// browser is a cookie-keeping client for the settings page. Its forms say they're same-origin,
// as a browser would.
type browser struct {
	t      *testing.T
	base   string
	client *http.Client
}

func newBrowser(c *client) *browser {
	jar, _ := cookiejar.New(nil)
	return &browser{c.t, c.server.URL, &http.Client{Jar: jar}}
}

func (b *browser) get(path string) string {
	b.t.Helper()
	res, err := b.client.Get(b.base + path)
	if err != nil {
		b.t.Fatal(err)
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(res.Body)
	return string(body)
}

func (b *browser) post(path string, form url.Values) string {
	b.t.Helper()
	req, _ := http.NewRequest("POST", b.base+path, strings.NewReader(form.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Sec-Fetch-Site", "same-origin")
	res, err := b.client.Do(req)
	if err != nil {
		b.t.Fatal(err)
	}
	defer res.Body.Close()
	body, _ := io.ReadAll(res.Body)
	return string(body)
}

// cookie is the token this browser holds.
func (b *browser) cookie() string {
	u, _ := url.Parse(b.base)
	for _, c := range b.client.Jar.Cookies(u) {
		if c.Name == "anti_social_token" {
			return c.Value
		}
	}
	return ""
}

func TestSettingsPageSignsInWithTheToken(t *testing.T) {
	c := setup(t)
	b := newBrowser(c)
	get := func() string { return b.get("/") }
	post := b.post

	if page := get(); !strings.Contains(page, "Sign in") {
		t.Fatalf("signed out: %s", page)
	}
	if page := post("/sign-in", url.Values{"token": {"wrong"}}); !strings.Contains(page, "doesn&#39;t match") {
		t.Fatalf("wrong token: %s", page)
	}
	if page := post("/sign-in", url.Values{"token": {token}}); !strings.Contains(page, "left today") {
		t.Fatalf("signed in: %s", page)
	}
	page := post("/settings", url.Values{"daily_minutes": {"5"}, "session_gap_minutes": {"20"}})
	if !strings.Contains(page, "Tighter limits apply now") || !strings.Contains(page, `value="5"`) {
		t.Fatalf("tightened: %s", page)
	}
	page = post("/settings", url.Values{"daily_minutes": {"60"}, "session_gap_minutes": {"20"}})
	if !strings.Contains(page, "Waiting to apply") {
		t.Fatalf("loosened: %s", page)
	}

	// Another site can't submit the form, whatever the cookie would allow.
	req, _ := http.NewRequest("POST", c.server.URL+"/settings", strings.NewReader("daily_minutes=1"))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Sec-Fetch-Site", "cross-site")
	res, _ := b.client.Do(req)
	if res.StatusCode != 403 {
		t.Fatalf("cross-site form: %d", res.StatusCode)
	}

	// The browser holds a token of its own, not the one it signed in with, and signing out
	// revokes it.
	own := b.cookie()
	if own == "" || own == token {
		t.Fatalf("cookie holds %q", own)
	}
	if page := post("/sign-out", nil); !strings.Contains(page, "Sign in") {
		t.Fatalf("signed out again: %s", page)
	}
	c.token = own
	c.expect("GET", "/api/status", nil, 401)
	c.token = token
	c.expect("GET", "/api/status", nil, 200)
}

func TestATVPairsThroughTheSettingsPage(t *testing.T) {
	c := setup(t)
	tv := &client{t, c.server, ""}
	started := tv.expect("POST", "/api/pairings", map[string]string{"name": "Fire TV"}, 201)
	code, secret := started["code"].(string), started["secret"].(string)
	if len(code) != 9 || code[4] != '-' || len(secret) != 64 {
		t.Fatalf("started: %v", started)
	}
	claim := map[string]string{"secret": secret}
	if out := tv.expect("POST", "/api/pairings/claim", claim, 200); out["state"] != "waiting" || out["token"] != nil {
		t.Fatalf("before approval: %v", out)
	}

	// The QR code's link, signed out: sign in, then back to approve.
	b := newBrowser(c)
	page := b.get("/pair?code=" + code)
	if !strings.Contains(page, "Sign in") || !strings.Contains(page, `name="next" value="/pair?code=`+code) {
		t.Fatalf("signed out: %s", page)
	}
	page = b.post("/sign-in", url.Values{"token": {token}, "next": {"/pair?code=" + code}})
	if !strings.Contains(page, "Pair a device") || !strings.Contains(page, `value="`+code+`"`) {
		t.Fatalf("after signing in: %s", page)
	}
	if page := b.post("/pair", url.Values{"code": {"ZZZZ-ZZZZ"}}); !strings.Contains(page, "No device is waiting") {
		t.Fatalf("wrong code: %s", page)
	}
	// Typed by hand: lower case, no dash.
	page = b.post("/pair", url.Values{"code": {strings.ToLower(strings.ReplaceAll(code, "-", ""))}})
	if !strings.Contains(page, "Fire TV is paired") {
		t.Fatalf("approved: %s", page)
	}

	out := tv.expect("POST", "/api/pairings/claim", claim, 200)
	tv.token, _ = out["token"].(string)
	if out["state"] != "paired" || len(tv.token) != 64 {
		t.Fatalf("claimed: %v", out)
	}
	tv.expect("GET", "/api/status", nil, 200)
	// Handed over once.
	tv.expect("POST", "/api/pairings/claim", claim, 404)

	// It's listed, and removing it from the page signs it out.
	page = b.get("/")
	if !strings.Contains(page, "<strong>Fire TV</strong>") {
		t.Fatalf("devices: %s", page)
	}
	id := regexp.MustCompile(`/devices/(\d+)/remove`).FindAllStringSubmatch(page, -1)
	if len(id) != 2 { // the TV and this browser; the configured token has no button
		t.Fatalf("remove buttons: %v", id)
	}
	b.post("/devices/"+id[1][1]+"/remove", nil)
	tv.expect("GET", "/api/status", nil, 401)
}

func TestPairingRefusesTheAfterSignInRedirectOffSite(t *testing.T) {
	c := setup(t)
	b := newBrowser(c)
	req, _ := http.NewRequest("POST", c.server.URL+"/sign-in",
		strings.NewReader(url.Values{"token": {token}, "next": {"//evil.example/pair"}}.Encode()))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Sec-Fetch-Site", "same-origin")
	b.client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	res, err := b.client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	if got := res.Header.Get("Location"); got != "/" {
		t.Fatalf("redirected to %q", got)
	}
}

func TestHealthNeedsNoToken(t *testing.T) {
	c := setup(t)
	res, err := http.Get(c.server.URL + "/healthz")
	if err != nil || res.StatusCode != 200 {
		t.Fatalf("healthz: %v %v", res, err)
	}
}

func itoa(n int) string { return strconv.Itoa(n) }

// errorCode is the code in an error body: {"error": {"code": ..., "message": ...}}.
func errorCode(body map[string]any) any {
	e, _ := body["error"].(map[string]any)
	return e["code"]
}

func TestUnknownAPIRoutesAnswerInJSON(t *testing.T) {
	c := setup(t)
	if body := c.expect("GET", "/api/nope", nil, 404); errorCode(body) != "not_found" {
		t.Fatalf("unknown route: %v", body)
	}
}
