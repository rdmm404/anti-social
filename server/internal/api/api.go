// Package api is the server's HTTP surface: the JSON API clients use, and the settings page.
// The rules live in ledger and library; this only checks input and maps errors.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/rdmm404/anti-social/server/internal/accounts"
	"github.com/rdmm404/anti-social/server/internal/ledger"
	"github.com/rdmm404/anti-social/server/internal/library"
)

var (
	videoID   = regexp.MustCompile(`^[\w-]{11}$`)
	channelID = regexp.MustCompile(`^(UC[\w-]{20,}|@[\w.-]+)$`)
	day       = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)
)

const (
	historyPage = 50
	// A check-in covers about 15 seconds, so this is generous for a client that was offline.
	maxIntervals = 500
	// Longer than any video: reported positions past this are nonsense.
	maxPosition = 48 * 3600
	maxBody     = 64 << 10
	cookieName  = "anti_social_token"
)

type Server struct {
	accounts *accounts.Accounts
	ledger   *ledger.Ledger
	library  *library.Library
	log      *slog.Logger
}

func New(a *accounts.Accounts, l *ledger.Ledger, lib *library.Library, log *slog.Logger) *Server {
	return &Server{accounts: a, ledger: l, library: lib, log: log}
}

type accountKey struct{}

func accountOf(r *http.Request) int64 { return r.Context().Value(accountKey{}).(int64) }

// token comes from the Authorization header, which apps send, or the settings page's cookie.
func token(r *http.Request) string {
	if auth, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer "); ok {
		return strings.TrimSpace(auth)
	}
	if c, err := r.Cookie(cookieName); err == nil {
		return c.Value
	}
	return ""
}

// authenticated runs next with the device's account, or answers 401.
func (s *Server) authenticated(next func(http.ResponseWriter, *http.Request)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, err := s.accounts.Authenticate(r.Context(), token(r))
		if errors.Is(err, accounts.ErrUnknownToken) {
			writeError(w, http.StatusUnauthorized, "unauthorized", "This device isn't signed in.")
			return
		}
		if err != nil {
			s.fail(w, r, err)
			return
		}
		next(w, r.WithContext(context.WithValue(r.Context(), accountKey{}, id)))
	}
}

func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	api := func(pattern string, h func(http.ResponseWriter, *http.Request)) {
		mux.Handle(pattern, s.authenticated(h))
	}
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { io.WriteString(w, "ok\n") })

	api("GET /api/status", s.status)
	api("POST /api/session", s.startSession)
	api("POST /api/checkins", s.checkin)
	api("GET /api/settings", s.settings)
	api("POST /api/settings", s.changeSettings)
	api("DELETE /api/settings/pending/{id}", s.cancelChange)
	api("GET /api/history", s.history)
	api("GET /api/history/videos", s.watchedVideos)
	api("GET /api/subscriptions", s.subscriptions)
	api("POST /api/subscriptions", s.subscribe)
	api("DELETE /api/subscriptions/{channel}", s.unsubscribe)
	api("GET /api/playlists", s.playlists)
	api("POST /api/playlists", s.createPlaylist)
	api("GET /api/playlists/{id}", s.playlist)
	api("DELETE /api/playlists/{id}", s.deletePlaylist)
	api("POST /api/playlists/{id}/items", s.addToPlaylist)
	api("DELETE /api/playlists/{id}/items/{video}", s.removeFromPlaylist)
	mux.HandleFunc("/api/", func(w http.ResponseWriter, _ *http.Request) {
		writeError(w, http.StatusNotFound, "not_found", "No such endpoint.")
	})

	s.pageRoutes(mux)
	return s.logged(mux)
}

func (s *Server) logged(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &recorder{ResponseWriter: w, code: http.StatusOK}
		next.ServeHTTP(rec, r)
		if r.URL.Path != "/healthz" {
			s.log.Info("request", "method", r.Method, "path", r.URL.Path, "status", rec.code, "ms", time.Since(start).Milliseconds())
		}
	})
}

type recorder struct {
	http.ResponseWriter
	code int
}

func (r *recorder) WriteHeader(code int) {
	r.code = code
	r.ResponseWriter.WriteHeader(code)
}

// ---- responses ----

func writeJSON(w http.ResponseWriter, code int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(code)
	json.NewEncoder(w).Encode(body)
}

func writeError(w http.ResponseWriter, code int, kind, message string) {
	writeJSON(w, code, map[string]string{"error": kind, "message": message})
}

// fail maps what the rules refused to 409 and anything unexpected to a logged 500.
func (s *Server) fail(w http.ResponseWriter, r *http.Request, err error) {
	var refusal *ledger.Refusal
	var problem *library.Problem
	switch {
	case errors.As(err, &refusal):
		writeError(w, http.StatusConflict, "refused", refusal.Message)
	case errors.As(err, &problem):
		writeError(w, http.StatusConflict, "refused", problem.Message)
	case errors.Is(err, library.ErrNotFound), errors.Is(err, ledger.ErrNoAccount):
		writeError(w, http.StatusNotFound, "not_found", "Not found.")
	default:
		s.log.Error("request failed", "method", r.Method, "path", r.URL.Path, "err", err)
		writeError(w, http.StatusInternalServerError, "server", "Something went wrong on the server.")
	}
}

func badRequest(w http.ResponseWriter, message string) {
	writeError(w, http.StatusBadRequest, "bad_request", message)
}

func decode(w http.ResponseWriter, r *http.Request, into any) bool {
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBody))
	if err := dec.Decode(into); err != nil {
		badRequest(w, "The request body isn't valid JSON for this endpoint.")
		return false
	}
	return true
}

func pathID(w http.ResponseWriter, r *http.Request, name string) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue(name), 10, 64)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", "Not found.")
		return 0, false
	}
	return id, true
}

// ---- status, sessions, check-ins ----

func (s *Server) status(w http.ResponseWriter, r *http.Request) {
	status, err := s.ledger.Status(r.Context(), accountOf(r))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, status)
}

func (s *Server) startSession(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Videos int `json:"videos"`
	}
	if !decode(w, r, &body) {
		return
	}
	status, err := s.ledger.StartSession(r.Context(), accountOf(r), body.Videos)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, status)
}

type checkinBody struct {
	VideoID string            `json:"video_id"`
	Title   string            `json:"title"`
	Channel string            `json:"channel"`
	Watched []ledger.Interval `json:"watched"`
}

type checkinResponse struct {
	Allowed bool    `json:"allowed"`
	Reason  *string `json:"reason"`
	// Unix seconds. Playback stops here unless another check-in succeeds first.
	ExpiresAt float64       `json:"expires_at"`
	Status    ledger.Status `json:"status"`
}

func (s *Server) checkin(w http.ResponseWriter, r *http.Request) {
	var body checkinBody
	if !decode(w, r, &body) {
		return
	}
	if !videoID.MatchString(body.VideoID) {
		badRequest(w, "Not a video id.")
		return
	}
	if len(body.Watched) > maxIntervals {
		badRequest(w, "Too many stretches in one check-in.")
		return
	}
	for _, iv := range body.Watched {
		if iv[0] < 0 || iv[1] < iv[0] || iv[1] > maxPosition {
			badRequest(w, "Watched stretches must run forwards, within the video.")
			return
		}
	}
	result, err := s.ledger.Checkin(r.Context(), accountOf(r), ledger.Checkin{
		VideoID: body.VideoID,
		Title:   clip(body.Title, library.MaxTitle),
		Channel: clip(body.Channel, library.MaxTitle),
		Watched: body.Watched,
	})
	if err != nil {
		s.fail(w, r, err)
		return
	}
	response := checkinResponse{
		Allowed:   result.Allowed,
		ExpiresAt: float64(result.ExpiresAt.UnixMilli()) / 1000,
		Status:    result.Status,
	}
	if result.Reason != "" {
		response.Reason = &result.Reason
	}
	writeJSON(w, http.StatusOK, response)
}

func clip(s string, n int) string {
	s = strings.TrimSpace(s)
	if r := []rune(s); len(r) > n {
		return string(r[:n])
	}
	return s
}

// ---- settings and history ----

func (s *Server) settings(w http.ResponseWriter, r *http.Request) {
	view, err := s.ledger.Settings(r.Context(), accountOf(r))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, view)
}

func (s *Server) changeSettings(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Changes map[string]int `json:"changes"`
	}
	if !decode(w, r, &body) {
		return
	}
	result, err := s.ledger.ChangeSettings(r.Context(), accountOf(r), body.Changes)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, result)
}

func (s *Server) cancelChange(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	if err := s.ledger.CancelChange(r.Context(), accountOf(r), id); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) history(w http.ResponseWriter, r *http.Request) {
	h, err := s.ledger.History(r.Context(), accountOf(r), 7)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, h)
}

func (s *Server) watchedVideos(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	query := ledger.WatchedQuery{Text: clip(q.Get("q"), 200), StartDay: q.Get("start"), EndDay: q.Get("end"), Limit: historyPage}
	for _, d := range []string{query.StartDay, query.EndDay} {
		if d != "" && !day.MatchString(d) {
			badRequest(w, "Dates must look like 2026-11-03.")
			return
		}
	}
	if offset := q.Get("offset"); offset != "" {
		n, err := strconv.Atoi(offset)
		if err != nil {
			badRequest(w, "offset must be a number.")
			return
		}
		query.Offset = max(0, n)
	}
	videos, err := s.ledger.SearchWatched(r.Context(), accountOf(r), query)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"videos": videos})
}

// ---- subscriptions ----

func (s *Server) subscriptions(w http.ResponseWriter, r *http.Request) {
	subs, err := s.library.Subscriptions(r.Context(), accountOf(r))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"subscriptions": subs})
}

func (s *Server) subscribe(w http.ResponseWriter, r *http.Request) {
	var body library.Subscription
	if !decode(w, r, &body) {
		return
	}
	if !channelID.MatchString(body.ChannelID) {
		badRequest(w, "Not a channel id.")
		return
	}
	if err := s.library.Subscribe(r.Context(), accountOf(r), body.ChannelID, body.Name); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) unsubscribe(w http.ResponseWriter, r *http.Request) {
	if err := s.library.Unsubscribe(r.Context(), accountOf(r), r.PathValue("channel")); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// ---- playlists ----

func (s *Server) playlists(w http.ResponseWriter, r *http.Request) {
	lists, err := s.library.Playlists(r.Context(), accountOf(r))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"playlists": lists})
}

func (s *Server) createPlaylist(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Name string `json:"name"`
	}
	if !decode(w, r, &body) {
		return
	}
	id, err := s.library.CreatePlaylist(r.Context(), accountOf(r), body.Name)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]int64{"id": id})
}

func (s *Server) playlist(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	p, err := s.library.Playlist(r.Context(), accountOf(r), id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, p)
}

func (s *Server) deletePlaylist(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	if err := s.library.DeletePlaylist(r.Context(), accountOf(r), id); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) addToPlaylist(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	var item library.Item
	if !decode(w, r, &item) {
		return
	}
	if !videoID.MatchString(item.ID) {
		badRequest(w, "Not a video id.")
		return
	}
	if err := s.library.AddToPlaylist(r.Context(), accountOf(r), id, []library.Item{item}); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) removeFromPlaylist(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	if err := s.library.RemoveFromPlaylist(r.Context(), accountOf(r), id, r.PathValue("video")); err != nil {
		s.fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
