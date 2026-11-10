// Package memory keeps budgets in memory, for tests. It behaves as the Postgres repository
// does, which the budget's tests check by running against both.
package memory

import (
	"cmp"
	"context"
	"maps"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/rdmm404/anti-social/server/internal/budget"
)

type key struct {
	account int64
	day     string
	video   string
}

type session struct {
	budget.Session
	account int64
}

type play struct {
	session int64
	video   string
}

type state struct {
	known    map[int64]bool
	settings map[int64]map[string]int
	pending  map[int64]budget.PendingChange // by id
	accounts map[int64]int64                // pending change id → account
	sessions map[int64]session
	plays    map[play]budget.SessionVideo
	watched  map[key]budget.Watched
	nextID   int64
}

func (s *state) clone() *state {
	c := *s
	c.known = maps.Clone(s.known)
	c.settings = make(map[int64]map[string]int, len(s.settings))
	for account, values := range s.settings {
		c.settings[account] = maps.Clone(values)
	}
	c.pending = maps.Clone(s.pending)
	c.accounts = maps.Clone(s.accounts)
	c.sessions = maps.Clone(s.sessions)
	c.plays = maps.Clone(s.plays)
	c.watched = make(map[key]budget.Watched, len(s.watched))
	for k, w := range s.watched {
		w.Intervals = slices.Clone(w.Intervals)
		c.watched[k] = w
	}
	return &c
}

// Repository holds one lock for every account, which is more holding than Postgres does but
// the same as far as any one account can tell.
type Repository struct {
	mu sync.Mutex
	s  *state
}

var _ budget.Repository = (*Repository)(nil)

func New() *Repository {
	return &Repository{s: &state{
		known:    map[int64]bool{},
		settings: map[int64]map[string]int{},
		pending:  map[int64]budget.PendingChange{},
		accounts: map[int64]int64{},
		sessions: map[int64]session{},
		plays:    map[play]budget.SessionVideo{},
		watched:  map[key]budget.Watched{},
	}}
}

// AddAccount makes an account known, as the accounts table does for Postgres.
func (r *Repository) AddAccount(id int64) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.s.known[id] = true
}

func (r *Repository) Within(_ context.Context, accountID int64, fn func(budget.Records) error) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !r.s.known[accountID] {
		return budget.ErrNoAccount
	}
	// fn works on a copy, which replaces the state only if fn succeeds.
	working := r.s.clone()
	if err := fn(&records{working, accountID}); err != nil {
		return err
	}
	r.s = working
	return nil
}

func (r *Repository) DayTotals(_ context.Context, accountID int64, start, end string) (map[string]float64, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	totals := map[string]float64{}
	for k, w := range r.s.watched {
		if k.account == accountID && k.day >= start && k.day <= end {
			totals[k.day] += w.ChargedSeconds
		}
	}
	return totals, nil
}

func (r *Repository) SearchWatched(_ context.Context, accountID int64, q budget.WatchedQuery) ([]budget.WatchedVideo, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	text := strings.ToLower(strings.TrimSpace(q.Text))
	var found []budget.Watched
	for k, w := range r.s.watched {
		if k.account != accountID ||
			(q.StartDay != "" && k.day < q.StartDay) || (q.EndDay != "" && k.day > q.EndDay) {
			continue
		}
		if text != "" && !strings.Contains(strings.ToLower(w.Title), text) && !strings.Contains(strings.ToLower(w.Channel), text) {
			continue
		}
		found = append(found, w)
	}
	slices.SortFunc(found, func(a, b budget.Watched) int {
		return cmp.Or(cmp.Compare(b.Day, a.Day), b.LastAt.Compare(a.LastAt))
	})
	videos := []budget.WatchedVideo{}
	for i, w := range found {
		if i < q.Offset {
			continue
		}
		if len(videos) == q.Limit {
			break
		}
		videos = append(videos, budget.WatchedVideo{Day: w.Day, VideoID: w.VideoID, Title: w.Title, Channel: w.Channel, WatchedSeconds: w.ChargedSeconds})
	}
	return videos, nil
}

type records struct {
	s       *state
	account int64
}

func (r *records) SavedSettings() (map[string]int, error) {
	return maps.Clone(r.s.settings[r.account]), nil
}

func (r *records) SaveSetting(key string, value int) error {
	if r.s.settings[r.account] == nil {
		r.s.settings[r.account] = map[string]int{}
	}
	r.s.settings[r.account][key] = value
	return nil
}

func (r *records) PendingChanges() ([]budget.PendingChange, error) {
	changes := []budget.PendingChange{}
	for id, c := range r.s.pending {
		if r.s.accounts[id] == r.account {
			changes = append(changes, c)
		}
	}
	slices.SortFunc(changes, func(a, b budget.PendingChange) int {
		return cmp.Or(a.ApplyAt.Compare(b.ApplyAt), cmp.Compare(a.ID, b.ID))
	})
	return changes, nil
}

func (r *records) QueueChange(c budget.PendingChange) error {
	r.s.nextID++
	c.ID = r.s.nextID
	r.s.pending[c.ID] = c
	r.s.accounts[c.ID] = r.account
	return nil
}

func (r *records) DeletePendingChange(id int64) error {
	if r.s.accounts[id] == r.account {
		delete(r.s.pending, id)
		delete(r.s.accounts, id)
	}
	return nil
}

func (r *records) lastPlayed(sessionID int64) *time.Time {
	var last *time.Time
	for p, v := range r.s.plays {
		if p.session == sessionID && (last == nil || v.LastAt.After(*last)) {
			at := v.LastAt
			last = &at
		}
	}
	return last
}

func (r *records) LatestSession(day string) (*budget.Session, error) {
	var latest *budget.Session
	for _, s := range r.s.sessions {
		if s.account == r.account && s.Day == day && (latest == nil || s.ID > latest.ID) {
			found := s.Session
			latest = &found
		}
	}
	if latest != nil {
		latest.LastPlayedAt = r.lastPlayed(latest.ID)
	}
	return latest, nil
}

func (r *records) CreateSession(s budget.Session) error {
	r.s.nextID++
	s.ID = r.s.nextID
	s.LastPlayedAt = nil
	r.s.sessions[s.ID] = session{s, r.account}
	return nil
}

func (r *records) owns(sessionID int64) bool {
	s, ok := r.s.sessions[sessionID]
	return ok && s.account == r.account
}

func (r *records) SessionVideos(sessionID int64) ([]budget.SessionVideo, error) {
	videos := []budget.SessionVideo{}
	if !r.owns(sessionID) {
		return videos, nil
	}
	for p, v := range r.s.plays {
		if p.session == sessionID {
			videos = append(videos, v)
		}
	}
	return videos, nil
}

func (r *records) Plays(day, videoID string) ([]budget.SessionVideo, error) {
	plays := []budget.SessionVideo{}
	for p, v := range r.s.plays {
		s := r.s.sessions[p.session]
		if p.video == videoID && s.account == r.account && s.Day == day {
			plays = append(plays, v)
		}
	}
	slices.SortFunc(plays, func(a, b budget.SessionVideo) int { return cmp.Compare(b.SessionID, a.SessionID) })
	return plays, nil
}

func (r *records) SavePlay(sessionID int64, videoID string, at time.Time) error {
	if !r.owns(sessionID) {
		return nil
	}
	p := play{sessionID, videoID}
	v, ok := r.s.plays[p]
	if !ok {
		v = budget.SessionVideo{SessionID: sessionID, VideoID: videoID, FirstAt: at}
	}
	v.LastAt = at
	r.s.plays[p] = v
	return nil
}

func (r *records) LastPlayedAt() (*time.Time, error) {
	var last *time.Time
	for id, s := range r.s.sessions {
		if s.account != r.account {
			continue
		}
		if at := r.lastPlayed(id); at != nil && (last == nil || at.After(*last)) {
			last = at
		}
	}
	return last, nil
}

func (r *records) UsedSeconds(day string) (float64, error) {
	used := 0.0
	for k, w := range r.s.watched {
		if k.account == r.account && k.day == day {
			used += w.ChargedSeconds
		}
	}
	return used, nil
}

func (r *records) Watched(day, videoID string) (*budget.Watched, error) {
	w, ok := r.s.watched[key{r.account, day, videoID}]
	if !ok {
		return nil, nil
	}
	w.Intervals = slices.Clone(w.Intervals)
	return &w, nil
}

func (r *records) SaveWatched(w budget.Watched) error {
	w.Intervals = slices.Clone(w.Intervals)
	r.s.watched[key{r.account, w.Day, w.VideoID}] = w
	return nil
}
