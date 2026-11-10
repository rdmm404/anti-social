// Package api is the budget's JSON API: status, sessions, check-ins, settings and history.
package api

import (
	"net/http"
	"strconv"

	"github.com/rdmm404/anti-social/server/internal/budget"
	"github.com/rdmm404/anti-social/server/internal/httpapi"
)

const historyPage = 50

// ---- wire types. Times are Unix seconds. ----

type SessionLeft struct {
	VideosLeft  int `json:"videos_left"`
	SamplesLeft int `json:"samples_left"`
}

type Status struct {
	BudgetSeconds    int64        `json:"budget_seconds"`
	UsedSeconds      int64        `json:"used_seconds"`
	RemainingSeconds int64        `json:"remaining_seconds"`
	CanWatch         bool         `json:"can_watch"`
	ResetsAt         float64      `json:"resets_at"`
	State            string       `json:"state"`
	Session          *SessionLeft `json:"session"`
	NextSessionAt    *float64     `json:"next_session_at"`
	SessionChoices   []int        `json:"session_choices"`
	SampleSeconds    int          `json:"sample_seconds"`
	CheckinSeconds   int          `json:"checkin_seconds"`
	Now              float64      `json:"now"`
}

type StartSessionRequest struct {
	Videos int `json:"videos"`
}

type CheckinRequest struct {
	VideoID string `json:"video_id"`
	Title   string `json:"title"`
	Channel string `json:"channel"`
	// [start, end] stretches, in seconds from the video's beginning.
	Watched [][2]float64 `json:"watched"`
}

type CheckinResponse struct {
	Allowed bool `json:"allowed"`
	// "out_of_time" or "no_session" when not allowed.
	Reason *string `json:"reason"`
	// Playback stops here unless another check-in succeeds first.
	ExpiresAt float64 `json:"expires_at"`
	Status    Status  `json:"status"`
}

type PendingChange struct {
	ID      int64   `json:"id"`
	Key     string  `json:"key"`
	Value   int     `json:"value"`
	ApplyAt float64 `json:"apply_at"`
}

type SettingsResponse struct {
	Settings        map[string]int  `json:"settings"`
	Pending         []PendingChange `json:"pending"`
	CoolingOffHours int             `json:"cooling_off_hours"`
}

type ChangeSettingsRequest struct {
	Changes map[string]int `json:"changes"`
}

type ChangeSettingsResponse struct {
	Applied []string `json:"applied"`
	Queued  []string `json:"queued"`
}

type DayTotal struct {
	Day            string `json:"day"`
	WatchedSeconds int64  `json:"watched_seconds"`
}

type WatchedVideo struct {
	Day            string  `json:"day"`
	VideoID        string  `json:"video_id"`
	Title          string  `json:"title"`
	Channel        string  `json:"channel"`
	WatchedSeconds float64 `json:"watched_seconds"`
}

type HistoryResponse struct {
	Days  []DayTotal     `json:"days"`
	Today []WatchedVideo `json:"today"`
}

type WatchedVideosResponse struct {
	Videos []WatchedVideo `json:"videos"`
}

// ---- mapping ----

func statusOf(s budget.Status) Status {
	out := Status{
		BudgetSeconds:    s.BudgetSeconds,
		UsedSeconds:      s.UsedSeconds,
		RemainingSeconds: s.RemainingSeconds,
		CanWatch:         s.CanWatch,
		ResetsAt:         httpapi.Unix(s.ResetsAt),
		State:            string(s.State),
		SessionChoices:   s.SessionChoices,
		SampleSeconds:    s.SampleSeconds,
		CheckinSeconds:   s.CheckinSeconds,
		Now:              httpapi.Unix(s.Now),
	}
	if s.Session != nil {
		out.Session = &SessionLeft{s.Session.VideosLeft, s.Session.SamplesLeft}
	}
	if s.NextSessionAt != nil {
		at := httpapi.Unix(*s.NextSessionAt)
		out.NextSessionAt = &at
	}
	return out
}

func videosOf(videos []budget.WatchedVideo) []WatchedVideo {
	out := make([]WatchedVideo, 0, len(videos))
	for _, v := range videos {
		out = append(out, WatchedVideo(v))
	}
	return out
}

// ---- handlers ----

type Handlers struct{ svc *budget.Service }

func New(svc *budget.Service) *Handlers { return &Handlers{svc} }

// Register adds the routes, each wrapped by auth.
func (h *Handlers) Register(mux *http.ServeMux, auth func(http.HandlerFunc) http.HandlerFunc) {
	mux.Handle("GET /api/status", auth(h.status))
	mux.Handle("POST /api/session", auth(h.startSession))
	mux.Handle("POST /api/checkins", auth(h.checkin))
	mux.Handle("GET /api/settings", auth(h.settings))
	mux.Handle("POST /api/settings", auth(h.changeSettings))
	mux.Handle("DELETE /api/settings/pending/{id}", auth(h.cancelChange))
	mux.Handle("GET /api/history", auth(h.history))
	mux.Handle("GET /api/history/videos", auth(h.watchedVideos))
}

func (h *Handlers) status(w http.ResponseWriter, r *http.Request) {
	status, err := h.svc.Status(r.Context(), httpapi.Account(r))
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	httpapi.WriteJSON(w, http.StatusOK, statusOf(status))
}

func (h *Handlers) startSession(w http.ResponseWriter, r *http.Request) {
	var body StartSessionRequest
	if !httpapi.Decode(w, r, &body) {
		return
	}
	status, err := h.svc.StartSession(r.Context(), httpapi.Account(r), body.Videos)
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	httpapi.WriteJSON(w, http.StatusOK, statusOf(status))
}

func (h *Handlers) checkin(w http.ResponseWriter, r *http.Request) {
	var body CheckinRequest
	if !httpapi.Decode(w, r, &body) {
		return
	}
	c := budget.Checkin{VideoID: body.VideoID, Title: body.Title, Channel: body.Channel, Watched: make([]budget.Interval, 0, len(body.Watched))}
	for _, iv := range body.Watched {
		c.Watched = append(c.Watched, budget.Interval(iv))
	}
	result, err := h.svc.Checkin(r.Context(), httpapi.Account(r), c)
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	out := CheckinResponse{Allowed: result.Allowed, ExpiresAt: httpapi.Unix(result.ExpiresAt), Status: statusOf(result.Status)}
	if result.Reason != "" {
		reason := string(result.Reason)
		out.Reason = &reason
	}
	httpapi.WriteJSON(w, http.StatusOK, out)
}

func (h *Handlers) settings(w http.ResponseWriter, r *http.Request) {
	view, err := h.svc.Settings(r.Context(), httpapi.Account(r))
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	out := SettingsResponse{Settings: view.Values, Pending: make([]PendingChange, 0, len(view.Pending)), CoolingOffHours: int(view.CoolingOff.Hours())}
	for _, c := range view.Pending {
		out.Pending = append(out.Pending, PendingChange{c.ID, c.Key, c.Value, httpapi.Unix(c.ApplyAt)})
	}
	httpapi.WriteJSON(w, http.StatusOK, out)
}

func (h *Handlers) changeSettings(w http.ResponseWriter, r *http.Request) {
	var body ChangeSettingsRequest
	if !httpapi.Decode(w, r, &body) {
		return
	}
	result, err := h.svc.ChangeSettings(r.Context(), httpapi.Account(r), body.Changes)
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	httpapi.WriteJSON(w, http.StatusOK, ChangeSettingsResponse(result))
}

func (h *Handlers) cancelChange(w http.ResponseWriter, r *http.Request) {
	id, ok := httpapi.PathID(w, r, "id")
	if !ok {
		return
	}
	if err := h.svc.CancelChange(r.Context(), httpapi.Account(r), id); err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handlers) history(w http.ResponseWriter, r *http.Request) {
	history, err := h.svc.History(r.Context(), httpapi.Account(r), 7)
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	out := HistoryResponse{Days: make([]DayTotal, 0, len(history.Days)), Today: videosOf(history.Today)}
	for _, d := range history.Days {
		out.Days = append(out.Days, DayTotal(d))
	}
	httpapi.WriteJSON(w, http.StatusOK, out)
}

func (h *Handlers) watchedVideos(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	query := budget.WatchedQuery{Text: q.Get("q"), StartDay: q.Get("start"), EndDay: q.Get("end"), Limit: historyPage}
	if offset := q.Get("offset"); offset != "" {
		n, err := strconv.Atoi(offset)
		if err != nil {
			httpapi.BadRequest(w, "offset must be a number.")
			return
		}
		query.Offset = n
	}
	videos, err := h.svc.SearchWatched(r.Context(), httpapi.Account(r), query)
	if err != nil {
		httpapi.Fail(w, r, err)
		return
	}
	httpapi.WriteJSON(w, http.StatusOK, WatchedVideosResponse{videosOf(videos)})
}
