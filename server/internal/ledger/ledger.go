// Package ledger decides how much watching is allowed. It knows nothing about YouTube.
//
// Clients play video themselves and check in while it plays, reporting the stretches
// watched since their last check-in (ADR 0001). Time is charged for what's reported,
// and each second of a video is charged once per budget day, so replays, seeks back,
// retries and a second device never double-charge.
//
// Watching happens in sessions: a number of videos chosen before browsing, because the
// decision is easier to keep when it's made before anything tempting is on screen. A video
// takes up a slot once it has played for SampleDuration, so backing out of a wrong click is
// free, a few times per session. The next session opens a while after the last check-in,
// which is what stops one session running straight into another.
//
// Every rule is per account, and every operation runs in a transaction that locks the
// account's row, so two devices checking in at once see each other's charges.
package ledger

import (
	"context"
	"errors"
	"fmt"
	"math"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	MaxSessionVideos  = 3
	SamplesPerSession = 3
	// Measured in wall-clock time from the first check-in for a video to the latest.
	SampleDuration = 30 * time.Second
	// Longer than this with no check-in and the session's unused videos are gone, so a
	// session opened at lunch can't skip the question in the evening.
	SessionIdle = 30 * time.Minute
)

// Refusal is a request the rules turn down. Its message is safe to show to the user.
type Refusal struct{ Message string }

func (r *Refusal) Error() string { return r.Message }

func refuse(format string, args ...any) error { return &Refusal{fmt.Sprintf(format, args...)} }

// ErrNoAccount means the account the request named doesn't exist.
var ErrNoAccount = errors.New("no such account")

type Config struct {
	// Budget days are counted in this zone.
	Location *time.Location
	// The budget day rolls over at this local hour, so a 1am binge counts against the
	// evening it belongs to.
	DayStartHour int
	CoolingOff   time.Duration
	// How long a successful check-in lets the client keep playing without another.
	CheckinExpiry time.Duration
	// How often clients should check in while playing.
	CheckinEvery time.Duration
	// The values an account's settings start at.
	Seeds map[string]int
}

type Ledger struct {
	pool  *pgxpool.Pool
	cfg   Config
	clock func() time.Time
}

func New(pool *pgxpool.Pool, cfg Config, clock func() time.Time) *Ledger {
	if clock == nil {
		clock = time.Now
	}
	return &Ledger{pool: pool, cfg: cfg, clock: clock}
}

// account is one operation's view of an account: one transaction, one moment.
type account struct {
	ctx context.Context
	tx  pgx.Tx
	id  int64
	now time.Time
	cfg *Config
}

// within runs fn in a transaction holding the account's lock, with due settings changes applied.
func (l *Ledger) within(ctx context.Context, accountID int64, fn func(a *account) error) error {
	return pgx.BeginFunc(ctx, l.pool, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, "SELECT 1 FROM accounts WHERE id = $1 FOR UPDATE", accountID)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return ErrNoAccount
		}
		a := &account{ctx: ctx, tx: tx, id: accountID, now: l.clock(), cfg: &l.cfg}
		if err := a.applyDueChanges(); err != nil {
			return err
		}
		return fn(a)
	})
}

// ---- budget day ----

func (a *account) dayOf(t time.Time) time.Time {
	local := t.In(a.cfg.Location).Add(-time.Duration(a.cfg.DayStartHour) * time.Hour)
	return time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, a.cfg.Location)
}

func (a *account) day() string { return a.dayOf(a.now).Format(time.DateOnly) }

func (a *account) resetsAt() time.Time {
	d := a.dayOf(a.now)
	return time.Date(d.Year(), d.Month(), d.Day()+1, a.cfg.DayStartHour, 0, 0, 0, a.cfg.Location)
}

// ---- sessions ----

type session struct {
	id       int64
	videos   int
	activeAt time.Time
}

// currentSession is today's latest session, unless nothing has been watched in it for too long.
func (a *account) currentSession() (*session, error) {
	var s session
	err := a.tx.QueryRow(a.ctx, `
		SELECT s.id, s.videos, GREATEST(s.started_at, COALESCE(MAX(v.last_at), s.started_at))
		FROM sessions s LEFT JOIN session_videos v ON v.session_id = s.id
		WHERE s.account_id = $1 AND s.day = $2
		GROUP BY s.id ORDER BY s.id DESC LIMIT 1`,
		a.id, a.day(),
	).Scan(&s.id, &s.videos, &s.activeAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if a.now.Sub(s.activeAt) >= SessionIdle {
		return nil, nil
	}
	return &s, nil
}

// sessionCounts returns the videos that have taken up a slot and those still under the sample limit.
func (a *account) sessionCounts(sessionID int64) (kept, sampled int, err error) {
	rows, err := a.tx.Query(a.ctx,
		"SELECT first_at, last_at FROM session_videos WHERE session_id = $1", sessionID)
	if err != nil {
		return 0, 0, err
	}
	defer rows.Close()
	for rows.Next() {
		var first, last time.Time
		if err := rows.Scan(&first, &last); err != nil {
			return 0, 0, err
		}
		if last.Sub(first) >= SampleDuration {
			kept++
		} else {
			sampled++
		}
	}
	return kept, sampled, rows.Err()
}

// slotsUsed: once the free samples are gone, a video takes up a slot as soon as it starts.
func slotsUsed(kept, sampled int) int {
	return kept + max(0, sampled-SamplesPerSession)
}

func (a *account) hasRoom(s *session) (bool, error) {
	kept, sampled, err := a.sessionCounts(s.id)
	if err != nil {
		return false, err
	}
	return slotsUsed(kept, sampled) < s.videos, nil
}

// keptIn is the session in which this video took up a slot today, if it has.
func (a *account) keptIn(videoID string) (*int64, error) {
	var id int64
	err := a.tx.QueryRow(a.ctx, `
		SELECT v.session_id FROM session_videos v JOIN sessions s ON s.id = v.session_id
		WHERE s.account_id = $1 AND s.day = $2 AND v.video_id = $3
		  AND v.last_at - v.first_at >= make_interval(secs => $4)
		ORDER BY v.session_id DESC LIMIT 1`,
		a.id, a.day(), videoID, SampleDuration.Seconds(),
	).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &id, nil
}

func (a *account) nextSessionAt() (*time.Time, error) {
	var last *time.Time
	err := a.tx.QueryRow(a.ctx, `
		SELECT MAX(v.last_at) FROM session_videos v JOIN sessions s ON s.id = v.session_id
		WHERE s.account_id = $1`, a.id,
	).Scan(&last)
	if err != nil || last == nil {
		return nil, err
	}
	settings, err := a.settings()
	if err != nil {
		return nil, err
	}
	next := last.Add(time.Duration(settings[SessionGapMinutes]) * time.Minute)
	return &next, nil
}

// State is what stands between the account and watching. Only StateOpen lets it browse.
type State string

const (
	StateOpen   State = "open"
	StateChoose State = "choose"
	StateGap    State = "gap"
	StateNoTime State = "no_time"
)

func (a *account) state() (State, error) {
	canWatch, err := a.canWatch()
	if err != nil || !canWatch {
		return StateNoTime, err
	}
	s, err := a.currentSession()
	if err != nil {
		return "", err
	}
	if s != nil {
		room, err := a.hasRoom(s)
		if err != nil {
			return "", err
		}
		if room {
			return StateOpen, nil
		}
	}
	next, err := a.nextSessionAt()
	if err != nil {
		return "", err
	}
	if next != nil && a.now.Before(*next) {
		return StateGap, nil
	}
	return StateChoose, nil
}

func (l *Ledger) StartSession(ctx context.Context, accountID int64, videos int) (Status, error) {
	var status Status
	err := l.within(ctx, accountID, func(a *account) error {
		if videos < 1 || videos > MaxSessionVideos {
			return refuse("A session is 1 to %d videos.", MaxSessionVideos)
		}
		state, err := a.state()
		if err != nil {
			return err
		}
		switch state {
		case StateNoTime:
			return refuse("No time left today.")
		case StateOpen:
			return refuse("A session is already open.")
		case StateGap:
			return refuse("The next session isn't open yet.")
		}
		if _, err := a.tx.Exec(a.ctx,
			"INSERT INTO sessions (account_id, day, videos, started_at) VALUES ($1, $2, $3, $4)",
			a.id, a.day(), videos, a.now,
		); err != nil {
			return err
		}
		status, err = a.status()
		return err
	})
	return status, err
}

// mayOpen is whether this video can be played from wherever it has got to.
func (a *account) mayOpen(videoID string) (bool, error) {
	if ok, err := a.canWatch(); err != nil || !ok {
		return false, err
	}
	// A video that's been kept today resumes without a new slot, so pausing is free.
	if kept, err := a.keptIn(videoID); err != nil || kept != nil {
		return kept != nil, err
	}
	s, err := a.currentSession()
	if err != nil || s == nil {
		return false, err
	}
	var first time.Time
	err = a.tx.QueryRow(a.ctx,
		"SELECT first_at FROM session_videos WHERE session_id = $1 AND video_id = $2", s.id, videoID,
	).Scan(&first)
	if errors.Is(err, pgx.ErrNoRows) {
		return a.hasRoom(s)
	}
	if err != nil {
		return false, err
	}
	if a.now.Sub(first) < SampleDuration {
		return true, nil
	}
	// Playing on turns this sample into a kept video, so it needs a slot of its own.
	kept, sampled, err := a.sessionCounts(s.id)
	if err != nil {
		return false, err
	}
	return slotsUsed(kept+1, sampled-1) <= s.videos, nil
}

// recordPlaying notes that the video is playing now, in the session it belongs to.
func (a *account) recordPlaying(videoID string) error {
	sessionID, err := a.keptIn(videoID)
	if err != nil {
		return err
	}
	if sessionID == nil {
		s, err := a.currentSession()
		if err != nil || s == nil {
			return err
		}
		sessionID = &s.id
	}
	_, err = a.tx.Exec(a.ctx, `
		INSERT INTO session_videos (session_id, video_id, first_at, last_at) VALUES ($1, $2, $3, $3)
		ON CONFLICT (session_id, video_id) DO UPDATE SET last_at = excluded.last_at`,
		*sessionID, videoID, a.now,
	)
	return err
}

// ---- charging ----

func (a *account) usedSeconds() (float64, error) {
	var used float64
	err := a.tx.QueryRow(a.ctx,
		"SELECT COALESCE(SUM(charged_seconds), 0) FROM watched WHERE account_id = $1 AND day = $2",
		a.id, a.day(),
	).Scan(&used)
	return used, err
}

func (a *account) budgetSeconds() (float64, error) {
	settings, err := a.settings()
	return float64(settings[DailyMinutes] * 60), err
}

func (a *account) canWatch() (bool, error) {
	used, err := a.usedSeconds()
	if err != nil {
		return false, err
	}
	budget, err := a.budgetSeconds()
	return used < budget, err
}

// charge records reported stretches of a video and returns the seconds that were new today.
func (a *account) charge(videoID, title, channel string, reported []Interval) (float64, error) {
	var intervals []Interval
	err := a.tx.QueryRow(a.ctx,
		"SELECT intervals FROM watched WHERE account_id = $1 AND day = $2 AND video_id = $3",
		a.id, a.day(), videoID,
	).Scan(&intervals)
	if err != nil && !errors.Is(err, pgx.ErrNoRows) {
		return 0, err
	}
	added, merged := addAll(intervals, reported)
	if added <= 0 {
		return 0, nil
	}
	_, err = a.tx.Exec(a.ctx, `
		INSERT INTO watched (account_id, day, video_id, title, channel, intervals, charged_seconds, last_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		ON CONFLICT (account_id, day, video_id) DO UPDATE SET
			title = COALESCE(NULLIF(excluded.title, ''), watched.title),
			channel = COALESCE(NULLIF(excluded.channel, ''), watched.channel),
			intervals = excluded.intervals,
			charged_seconds = watched.charged_seconds + excluded.charged_seconds,
			last_at = excluded.last_at`,
		a.id, a.day(), videoID, title, channel, merged, added, a.now,
	)
	return added, err
}

// Checkin is a client's report of what it watched since its last check-in. A check-in with
// nothing watched asks whether a video may start or resume.
type Checkin struct {
	VideoID string
	Title   string
	Channel string
	Watched []Interval
}

type CheckinResult struct {
	Allowed bool
	// Why not, when not allowed: "out_of_time" or "no_session".
	Reason    string
	ExpiresAt time.Time
	Status    Status
}

const (
	ReasonOutOfTime = "out_of_time"
	ReasonNoSession = "no_session"
)

// Checkin charges what was watched, which was watched whatever the rules say now, and
// answers whether the video may keep playing until the expiry.
func (l *Ledger) Checkin(ctx context.Context, accountID int64, c Checkin) (CheckinResult, error) {
	var result CheckinResult
	err := l.within(ctx, accountID, func(a *account) error {
		// Asked before this check-in is recorded, as the old server asked before each delivery.
		before, err := a.mayOpen(c.VideoID)
		if err != nil {
			return err
		}
		if _, err := a.charge(c.VideoID, c.Title, c.Channel, c.Watched); err != nil {
			return err
		}
		if before {
			if err := a.recordPlaying(c.VideoID); err != nil {
				return err
			}
		}
		after, err := a.mayOpen(c.VideoID)
		if err != nil {
			return err
		}
		result.Allowed = before && after
		result.ExpiresAt = a.now
		if result.Allowed {
			result.ExpiresAt = a.now.Add(a.cfg.CheckinExpiry)
		} else if ok, err := a.canWatch(); err != nil {
			return err
		} else if !ok {
			result.Reason = ReasonOutOfTime
		} else {
			result.Reason = ReasonNoSession
		}
		result.Status, err = a.status()
		return err
	})
	return result, err
}

// ---- reading state ----

type SessionLeft struct {
	VideosLeft  int `json:"videos_left"`
	SamplesLeft int `json:"samples_left"`
}

// Status is everything a client needs to show where the account stands. Times are Unix seconds.
type Status struct {
	BudgetSeconds    int64        `json:"budget_seconds"`
	UsedSeconds      int64        `json:"used_seconds"`
	RemainingSeconds int64        `json:"remaining_seconds"`
	CanWatch         bool         `json:"can_watch"`
	ResetsAt         float64      `json:"resets_at"`
	State            State        `json:"state"`
	Session          *SessionLeft `json:"session"`
	NextSessionAt    *float64     `json:"next_session_at"`
	SessionChoices   []int        `json:"session_choices"`
	SampleSeconds    int          `json:"sample_seconds"`
	CheckinSeconds   int          `json:"checkin_seconds"`
	Now              float64      `json:"now"`
}

func unix(t time.Time) float64 { return float64(t.UnixMilli()) / 1000 }

func (a *account) status() (Status, error) {
	budget, err := a.budgetSeconds()
	if err != nil {
		return Status{}, err
	}
	used, err := a.usedSeconds()
	if err != nil {
		return Status{}, err
	}
	state, err := a.state()
	if err != nil {
		return Status{}, err
	}
	s := Status{
		BudgetSeconds:    int64(budget),
		UsedSeconds:      int64(math.Round(used)),
		RemainingSeconds: max(0, int64(math.Round(budget-used))),
		CanWatch:         used < budget,
		ResetsAt:         unix(a.resetsAt()),
		State:            state,
		SessionChoices:   make([]int, 0, MaxSessionVideos),
		SampleSeconds:    int(SampleDuration.Seconds()),
		CheckinSeconds:   int(a.cfg.CheckinEvery.Seconds()),
		Now:              unix(a.now),
	}
	for n := 1; n <= MaxSessionVideos; n++ {
		s.SessionChoices = append(s.SessionChoices, n)
	}
	current, err := a.currentSession()
	if err != nil {
		return Status{}, err
	}
	if current != nil {
		kept, sampled, err := a.sessionCounts(current.id)
		if err != nil {
			return Status{}, err
		}
		s.Session = &SessionLeft{
			VideosLeft:  max(0, current.videos-slotsUsed(kept, sampled)),
			SamplesLeft: max(0, SamplesPerSession-sampled),
		}
	}
	next, err := a.nextSessionAt()
	if err != nil {
		return Status{}, err
	}
	if next != nil {
		at := unix(*next)
		s.NextSessionAt = &at
	}
	return s, nil
}

func (l *Ledger) Status(ctx context.Context, accountID int64) (Status, error) {
	var status Status
	err := l.within(ctx, accountID, func(a *account) (err error) {
		status, err = a.status()
		return err
	})
	return status, err
}
