// Package api lets requests in by device token, and lets a device without one pair.
package api

import (
	"net/http"
	"time"

	"github.com/rdmm404/anti-social/server/internal/accounts"
	"github.com/rdmm404/anti-social/server/internal/httpapi"
)

// Authenticated runs next with the device's account on the request, or answers 401.
func Authenticated(svc *accounts.Service, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		id, err := svc.Authenticate(r.Context(), httpapi.Token(r))
		if err != nil {
			httpapi.Fail(w, r, err)
			return
		}
		next(w, r.WithContext(httpapi.WithAccount(r.Context(), id)))
	}
}

// ---- wire types ----

type StartPairingRequest struct {
	Name string `json:"name"`
}

// StartPairingResponse is what a device shows (code) and keeps (secret). It approves at
// /pair?code=CODE on the server, signed in.
type StartPairingResponse struct {
	Code      string  `json:"code"`
	Secret    string  `json:"secret"`
	ExpiresAt float64 `json:"expires_at"`
	// Seconds between claims while it waits.
	PollEvery int `json:"poll_every"`
}

type ClaimPairingRequest struct {
	Secret string `json:"secret"`
}

// ClaimPairingResponse says "waiting", or "paired" with the device's token. An expired or
// already claimed pairing is a 404.
type ClaimPairingResponse struct {
	State string `json:"state"`
	Token string `json:"token,omitempty"`
}

// ---- handlers ----

type Handlers struct{ svc *accounts.Service }

func New(svc *accounts.Service) *Handlers { return &Handlers{svc} }

// Register adds the pairing routes. They need no token: they're how a device gets one.
func (h *Handlers) Register(mux *http.ServeMux) {
	mux.HandleFunc("POST /api/pairings", h.start)
	mux.HandleFunc("POST /api/pairings/claim", h.claim)
}

const pollEvery = 3 * time.Second

func (h *Handlers) start(w http.ResponseWriter, r *http.Request) {
	var body StartPairingRequest
	if !httpapi.Decode(w, r, &body) {
		return
	}
	p, err := h.svc.StartPairing(r.Context(), body.Name)
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	httpapi.WriteJSON(w, http.StatusCreated, StartPairingResponse{
		Code:      accounts.FormatCode(p.Code),
		Secret:    p.Secret,
		ExpiresAt: httpapi.Unix(p.ExpiresAt),
		PollEvery: int(pollEvery.Seconds()),
	})
}

func (h *Handlers) claim(w http.ResponseWriter, r *http.Request) {
	var body ClaimPairingRequest
	if !httpapi.Decode(w, r, &body) {
		return
	}
	token, err := h.svc.FinishPairing(r.Context(), body.Secret)
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	if token == "" {
		httpapi.WriteJSON(w, http.StatusOK, ClaimPairingResponse{State: "waiting"})
		return
	}
	httpapi.WriteJSON(w, http.StatusOK, ClaimPairingResponse{State: "paired", Token: token})
}
