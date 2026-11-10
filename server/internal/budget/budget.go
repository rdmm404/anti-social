// Package budget decides how much watching is allowed. It knows nothing about YouTube.
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
// Every rule is per account. Anything that applies them runs with the account's records held
// (Repository.Within), so two devices checking in at once see each other's charges.
package budget

import (
	"time"

	"github.com/rdmm404/anti-social/server/internal/apperr"
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

var ErrNoAccount = apperr.New(apperr.NotFound, "No such account.")

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

// ---- what's stored ----

// Session is a number of videos chosen at once. Day is its budget day, as YYYY-MM-DD.
type Session struct {
	ID        int64
	Day       string
	Videos    int
	StartedAt time.Time
	// The latest check-in for any of its videos, if there's been one.
	LastPlayedAt *time.Time
}

// SessionVideo is a video played in a session, between its first and latest check-ins.
type SessionVideo struct {
	SessionID int64
	VideoID   string
	FirstAt   time.Time
	LastAt    time.Time
}

// Watched is what's been charged for one video on one budget day.
type Watched struct {
	Day       string
	VideoID   string
	Title     string
	Channel   string
	Intervals []Interval
	// The sum of what each check-in newly covered.
	ChargedSeconds float64
	LastAt         time.Time
}

// PendingChange is a loosened setting waiting out the cooling-off.
type PendingChange struct {
	ID          int64
	Key         string
	Value       int
	RequestedAt time.Time
	ApplyAt     time.Time
}

// ---- what's asked and answered ----

// Checkin is a client's report of what it watched since its last check-in. A check-in with
// nothing watched asks whether a video may start or resume.
type Checkin struct {
	VideoID string
	Title   string
	Channel string
	Watched []Interval
}

type Reason string

const (
	ReasonOutOfTime Reason = "out_of_time"
	ReasonNoSession Reason = "no_session"
)

type CheckinResult struct {
	Allowed bool
	// Why not, when not allowed.
	Reason Reason
	// Playback stops here unless another check-in succeeds first.
	ExpiresAt time.Time
	Status    Status
}

// State is what stands between the account and watching. Only StateOpen lets it browse.
type State string

const (
	StateOpen   State = "open"
	StateChoose State = "choose"
	StateGap    State = "gap"
	StateNoTime State = "no_time"
)

type SessionLeft struct {
	VideosLeft  int
	SamplesLeft int
}

// Status is everything a client needs to show where the account stands.
type Status struct {
	BudgetSeconds    int64
	UsedSeconds      int64
	RemainingSeconds int64
	CanWatch         bool
	ResetsAt         time.Time
	State            State
	Session          *SessionLeft
	NextSessionAt    *time.Time
	SessionChoices   []int
	SampleSeconds    int
	CheckinSeconds   int
	Now              time.Time
}

type SettingsView struct {
	Values     map[string]int
	Pending    []PendingChange
	CoolingOff time.Duration
}

type ChangeResult struct {
	Applied []string
	Queued  []string
}

type DayTotal struct {
	Day            string
	WatchedSeconds int64
}

type WatchedVideo struct {
	Day            string
	VideoID        string
	Title          string
	Channel        string
	WatchedSeconds float64
}

type History struct {
	// Newest first.
	Days  []DayTotal
	Today []WatchedVideo
}

type WatchedQuery struct {
	Text     string
	StartDay string // inclusive, YYYY-MM-DD, empty for no bound
	EndDay   string
	Limit    int
	Offset   int
}
