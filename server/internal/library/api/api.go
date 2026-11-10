// Package api is the library's JSON API.
package api

import (
	"net/http"

	"github.com/rdmm404/anti-social/server/internal/httpapi"
	"github.com/rdmm404/anti-social/server/internal/library"
)

// ---- wire types ----

type Subscription struct {
	ChannelID string `json:"channel_id"`
	Name      string `json:"name"`
}

type SubscriptionsResponse struct {
	Subscriptions []Subscription `json:"subscriptions"`
}

type PlaylistSummary struct {
	ID    int64  `json:"id"`
	Name  string `json:"name"`
	Count int    `json:"count"`
}

type PlaylistsResponse struct {
	Playlists []PlaylistSummary `json:"playlists"`
}

type Item struct {
	ID       string `json:"id"`
	Title    string `json:"title"`
	Channel  string `json:"channel"`
	Duration *int   `json:"duration"`
}

type Playlist struct {
	ID    int64  `json:"id"`
	Name  string `json:"name"`
	Items []Item `json:"items"`
}

type CreatePlaylistRequest struct {
	Name string `json:"name"`
}

type CreatePlaylistResponse struct {
	ID int64 `json:"id"`
}

// ---- handlers ----

type Handlers struct{ svc *library.Service }

func New(svc *library.Service) *Handlers { return &Handlers{svc} }

// Register adds the routes, each wrapped by auth.
func (h *Handlers) Register(mux *http.ServeMux, auth func(http.HandlerFunc) http.HandlerFunc) {
	mux.Handle("GET /api/subscriptions", auth(h.subscriptions))
	mux.Handle("POST /api/subscriptions", auth(h.subscribe))
	mux.Handle("DELETE /api/subscriptions/{channel}", auth(h.unsubscribe))
	mux.Handle("GET /api/playlists", auth(h.playlists))
	mux.Handle("POST /api/playlists", auth(h.createPlaylist))
	mux.Handle("GET /api/playlists/{id}", auth(h.playlist))
	mux.Handle("DELETE /api/playlists/{id}", auth(h.deletePlaylist))
	mux.Handle("POST /api/playlists/{id}/items", auth(h.addToPlaylist))
	mux.Handle("DELETE /api/playlists/{id}/items/{video}", auth(h.removeFromPlaylist))
}

func (h *Handlers) subscriptions(w http.ResponseWriter, r *http.Request) {
	subs, err := h.svc.Subscriptions(r.Context(), httpapi.Account(r))
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	out := SubscriptionsResponse{make([]Subscription, 0, len(subs))}
	for _, s := range subs {
		out.Subscriptions = append(out.Subscriptions, Subscription(s))
	}
	httpapi.WriteJSON(w, http.StatusOK, out)
}

func (h *Handlers) subscribe(w http.ResponseWriter, r *http.Request) {
	var body Subscription
	if !httpapi.Decode(w, r, &body) {
		return
	}
	if err := h.svc.Subscribe(r.Context(), httpapi.Account(r), library.Subscription(body)); err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handlers) unsubscribe(w http.ResponseWriter, r *http.Request) {
	if err := h.svc.Unsubscribe(r.Context(), httpapi.Account(r), r.PathValue("channel")); err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handlers) playlists(w http.ResponseWriter, r *http.Request) {
	lists, err := h.svc.Playlists(r.Context(), httpapi.Account(r))
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	out := PlaylistsResponse{make([]PlaylistSummary, 0, len(lists))}
	for _, p := range lists {
		out.Playlists = append(out.Playlists, PlaylistSummary(p))
	}
	httpapi.WriteJSON(w, http.StatusOK, out)
}

func (h *Handlers) createPlaylist(w http.ResponseWriter, r *http.Request) {
	var body CreatePlaylistRequest
	if !httpapi.Decode(w, r, &body) {
		return
	}
	id, err := h.svc.CreatePlaylist(r.Context(), httpapi.Account(r), body.Name)
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	httpapi.WriteJSON(w, http.StatusOK, CreatePlaylistResponse{id})
}

func (h *Handlers) playlist(w http.ResponseWriter, r *http.Request) {
	id, ok := httpapi.PathID(w, r, "id")
	if !ok {
		return
	}
	p, err := h.svc.Playlist(r.Context(), httpapi.Account(r), id)
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	out := Playlist{ID: p.ID, Name: p.Name, Items: make([]Item, 0, len(p.Items))}
	for _, i := range p.Items {
		out.Items = append(out.Items, Item{ID: i.VideoID, Title: i.Title, Channel: i.Channel, Duration: i.Duration})
	}
	httpapi.WriteJSON(w, http.StatusOK, out)
}

func (h *Handlers) deletePlaylist(w http.ResponseWriter, r *http.Request) {
	id, ok := httpapi.PathID(w, r, "id")
	if !ok {
		return
	}
	if err := h.svc.DeletePlaylist(r.Context(), httpapi.Account(r), id); err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handlers) addToPlaylist(w http.ResponseWriter, r *http.Request) {
	id, ok := httpapi.PathID(w, r, "id")
	if !ok {
		return
	}
	var body Item
	if !httpapi.Decode(w, r, &body) {
		return
	}
	item := library.Item{VideoID: body.ID, Title: body.Title, Channel: body.Channel, Duration: body.Duration}
	if err := h.svc.AddToPlaylist(r.Context(), httpapi.Account(r), id, []library.Item{item}); err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handlers) removeFromPlaylist(w http.ResponseWriter, r *http.Request) {
	id, ok := httpapi.PathID(w, r, "id")
	if !ok {
		return
	}
	if err := h.svc.RemoveFromPlaylist(r.Context(), httpapi.Account(r), id, r.PathValue("video")); err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
