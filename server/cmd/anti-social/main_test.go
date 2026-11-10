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
	accts := accounts.NewService(accountsmem.New())
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

func TestSettingsPageSignsInWithTheToken(t *testing.T) {
	c := setup(t)
	jar, _ := cookiejar.New(nil)
	browser := &http.Client{Jar: jar}
	get := func() string {
		res, err := browser.Get(c.server.URL + "/")
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return string(b)
	}
	post := func(path string, form url.Values) string {
		req, _ := http.NewRequest("POST", c.server.URL+path, strings.NewReader(form.Encode()))
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		req.Header.Set("Sec-Fetch-Site", "same-origin")
		res, err := browser.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return string(b)
	}

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
	res, _ := browser.Do(req)
	if res.StatusCode != 403 {
		t.Fatalf("cross-site form: %d", res.StatusCode)
	}

	if page := post("/sign-out", nil); !strings.Contains(page, "Sign in") {
		t.Fatalf("signed out again: %s", page)
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
