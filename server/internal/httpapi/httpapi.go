// Package httpapi is what every domain's JSON API shares: reading requests, writing answers
// and errors, and knowing which account a request is for.
package httpapi

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/rdmm404/anti-social/server/internal/apperr"
)

const (
	maxBody = 64 << 10
	// The settings page signs in with this cookie; apps send the token as a Bearer header.
	CookieName = "anti_social_token"
)

// ErrorResponse is the body of every failed request.
type ErrorResponse struct {
	Error ErrorBody `json:"error"`
}

type ErrorBody struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func WriteJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(body)
}

func WriteError(w http.ResponseWriter, status int, code, message string) {
	WriteJSON(w, status, ErrorResponse{ErrorBody{code, message}})
}

func BadRequest(w http.ResponseWriter, message string) {
	WriteError(w, http.StatusBadRequest, string(apperr.Invalid), message)
}

func NotFound(w http.ResponseWriter) {
	WriteError(w, http.StatusNotFound, string(apperr.NotFound), "Not found.")
}

var statuses = map[apperr.Kind]int{
	apperr.Invalid:      http.StatusBadRequest,
	apperr.Refused:      http.StatusConflict,
	apperr.NotFound:     http.StatusNotFound,
	apperr.Unauthorized: http.StatusUnauthorized,
}

// Fail answers with what an *apperr.Error says. Anything else is logged and answered with a
// bare 500, so no SQL or internals reach the client.
func Fail(w http.ResponseWriter, r *http.Request, err error) {
	if e, ok := apperr.As(err); ok {
		WriteError(w, statuses[e.Kind], string(e.Kind), e.Message)
		return
	}
	slog.Error("request failed", "method", r.Method, "path", r.URL.Path, "err", err)
	WriteError(w, http.StatusInternalServerError, "internal", "Something went wrong on the server.")
}

// Decode reads a JSON body, or answers 400 and returns false.
func Decode(w http.ResponseWriter, r *http.Request, into any) bool {
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxBody)).Decode(into); err != nil {
		BadRequest(w, "The request body isn't valid JSON for this endpoint.")
		return false
	}
	return true
}

// PathID reads a numeric path value, or answers 404 and returns false.
func PathID(w http.ResponseWriter, r *http.Request, name string) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue(name), 10, 64)
	if err != nil {
		NotFound(w)
		return 0, false
	}
	return id, true
}

// Unix is a time as the API sends it: seconds, with milliseconds.
func Unix(t time.Time) float64 { return float64(t.UnixMilli()) / 1000 }

// Token is the device token a request carries, from the Authorization header or the cookie.
func Token(r *http.Request) string {
	if auth, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer "); ok {
		return strings.TrimSpace(auth)
	}
	if c, err := r.Cookie(CookieName); err == nil {
		return c.Value
	}
	return ""
}

type accountKey struct{}

func WithAccount(ctx context.Context, id int64) context.Context {
	return context.WithValue(ctx, accountKey{}, id)
}

// Account is the account an authenticated request is for.
func Account(r *http.Request) int64 { return r.Context().Value(accountKey{}).(int64) }

// Logged logs each request, except health checks.
func Logged(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rec := &recorder{ResponseWriter: w, code: http.StatusOK}
		next.ServeHTTP(rec, r)
		if r.URL.Path != "/healthz" {
			slog.Info("request", "method", r.Method, "path", r.URL.Path, "status", rec.code, "ms", time.Since(start).Milliseconds())
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
