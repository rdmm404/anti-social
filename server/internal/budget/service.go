package budget

import (
	"context"
	"math"
	"regexp"
	"time"

	"github.com/rdmm404/anti-social/server/internal/apperr"
	"github.com/rdmm404/anti-social/server/internal/text"
)

const (
	maxTitle = 200
	// A check-in covers about 15 seconds, so this is generous for a client that was offline.
	maxIntervals = 500
	// Longer than any video: reported positions past this are nonsense.
	maxPosition = 48 * 3600
)

var videoID = regexp.MustCompile(`^[\w-]{11}$`)

type Service struct {
	repo  Repository
	cfg   Config
	clock func() time.Time
}

func NewService(repo Repository, cfg Config, clock func() time.Time) *Service {
	if clock == nil {
		clock = time.Now
	}
	return &Service{repo: repo, cfg: cfg, clock: clock}
}

func refuse(format string, args ...any) error { return apperr.New(apperr.Refused, format, args...) }

func invalid(format string, args ...any) error { return apperr.New(apperr.Invalid, format, args...) }

// op is one operation on an account: its held records, at one moment.
type op struct {
	r   Records
	now time.Time
	cfg *Config
}

// within runs fn with the account's records held and due settings changes applied.
func (s *Service) within(ctx context.Context, accountID int64, fn func(o *op) error) error {
	return s.repo.Within(ctx, accountID, func(r Records) error {
		o := &op{r: r, now: s.clock(), cfg: &s.cfg}
		if err := o.applyDueChanges(); err != nil {
			return err
		}
		return fn(o)
	})
}

// ---- budget day ----

// dayOf is the budget day t falls in, as local midnight.
func (c *Config) dayOf(t time.Time) time.Time {
	local := t.In(c.Location).Add(-time.Duration(c.DayStartHour) * time.Hour)
	return time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, c.Location)
}

func (o *op) day() string { return o.cfg.dayOf(o.now).Format(time.DateOnly) }

func (o *op) resetsAt() time.Time {
	d := o.cfg.dayOf(o.now)
	return time.Date(d.Year(), d.Month(), d.Day()+1, o.cfg.DayStartHour, 0, 0, 0, o.cfg.Location)
}

// ---- sessions ----

// currentSession is today's latest session, unless nothing has been watched in it for too long.
func (o *op) currentSession() (*Session, error) {
	s, err := o.r.LatestSession(o.day())
	if err != nil || s == nil {
		return nil, err
	}
	active := s.StartedAt
	if s.LastPlayedAt != nil && s.LastPlayedAt.After(active) {
		active = *s.LastPlayedAt
	}
	if o.now.Sub(active) >= SessionIdle {
		return nil, nil
	}
	return s, nil
}

func kept(v SessionVideo) bool { return v.LastAt.Sub(v.FirstAt) >= SampleDuration }

// sessionCounts returns the videos that have taken up a slot and those still under the sample limit.
func (o *op) sessionCounts(sessionID int64) (keptCount, sampled int, err error) {
	videos, err := o.r.SessionVideos(sessionID)
	if err != nil {
		return 0, 0, err
	}
	for _, v := range videos {
		if kept(v) {
			keptCount++
		} else {
			sampled++
		}
	}
	return keptCount, sampled, nil
}

// slotsUsed: once the free samples are gone, a video takes up a slot as soon as it starts.
func slotsUsed(kept, sampled int) int {
	return kept + max(0, sampled-SamplesPerSession)
}

func (o *op) hasRoom(s *Session) (bool, error) {
	k, sampled, err := o.sessionCounts(s.ID)
	if err != nil {
		return false, err
	}
	return slotsUsed(k, sampled) < s.Videos, nil
}

// keptIn is the session in which this video took up a slot today, if it has.
func (o *op) keptIn(videoID string) (*int64, error) {
	plays, err := o.r.Plays(o.day(), videoID)
	if err != nil {
		return nil, err
	}
	for _, p := range plays {
		if kept(p) {
			return &p.SessionID, nil
		}
	}
	return nil, nil
}

func (o *op) nextSessionAt() (*time.Time, error) {
	last, err := o.r.LastPlayedAt()
	if err != nil || last == nil {
		return nil, err
	}
	settings, err := o.settings()
	if err != nil {
		return nil, err
	}
	next := last.Add(time.Duration(settings[SessionGapMinutes]) * time.Minute)
	return &next, nil
}

func (o *op) state() (State, error) {
	canWatch, err := o.canWatch()
	if err != nil || !canWatch {
		return StateNoTime, err
	}
	s, err := o.currentSession()
	if err != nil {
		return "", err
	}
	if s != nil {
		room, err := o.hasRoom(s)
		if err != nil {
			return "", err
		}
		if room {
			return StateOpen, nil
		}
	}
	next, err := o.nextSessionAt()
	if err != nil {
		return "", err
	}
	if next != nil && o.now.Before(*next) {
		return StateGap, nil
	}
	return StateChoose, nil
}

func (s *Service) StartSession(ctx context.Context, accountID int64, videos int) (Status, error) {
	if videos < 1 || videos > MaxSessionVideos {
		return Status{}, invalid("A session is 1 to %d videos.", MaxSessionVideos)
	}
	var status Status
	err := s.within(ctx, accountID, func(o *op) error {
		state, err := o.state()
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
		if err := o.r.CreateSession(Session{Day: o.day(), Videos: videos, StartedAt: o.now}); err != nil {
			return err
		}
		status, err = o.status()
		return err
	})
	return status, err
}

// mayOpen is whether this video can be played from wherever it has got to.
func (o *op) mayOpen(videoID string) (bool, error) {
	if ok, err := o.canWatch(); err != nil || !ok {
		return false, err
	}
	// A video that's been kept today resumes without a new slot, so pausing is free.
	if k, err := o.keptIn(videoID); err != nil || k != nil {
		return k != nil, err
	}
	s, err := o.currentSession()
	if err != nil || s == nil {
		return false, err
	}
	videos, err := o.r.SessionVideos(s.ID)
	if err != nil {
		return false, err
	}
	var this *SessionVideo
	for i := range videos {
		if videos[i].VideoID == videoID {
			this = &videos[i]
		}
	}
	if this == nil {
		return o.hasRoom(s)
	}
	if o.now.Sub(this.FirstAt) < SampleDuration {
		return true, nil
	}
	// Playing on turns this sample into a kept video, so it needs a slot of its own.
	k, sampled, err := o.sessionCounts(s.ID)
	if err != nil {
		return false, err
	}
	return slotsUsed(k+1, sampled-1) <= s.Videos, nil
}

// recordPlaying notes that the video is playing now, in the session it belongs to.
func (o *op) recordPlaying(videoID string) error {
	sessionID, err := o.keptIn(videoID)
	if err != nil {
		return err
	}
	if sessionID == nil {
		s, err := o.currentSession()
		if err != nil || s == nil {
			return err
		}
		sessionID = &s.ID
	}
	return o.r.SavePlay(*sessionID, videoID, o.now)
}

// ---- charging ----

func (o *op) budgetSeconds() (float64, error) {
	settings, err := o.settings()
	return float64(settings[DailyMinutes] * 60), err
}

func (o *op) canWatch() (bool, error) {
	used, err := o.r.UsedSeconds(o.day())
	if err != nil {
		return false, err
	}
	budget, err := o.budgetSeconds()
	return used < budget, err
}

// charge records reported stretches of a video and returns the seconds that were new today.
func (o *op) charge(c Checkin) (float64, error) {
	w, err := o.r.Watched(o.day(), c.VideoID)
	if err != nil {
		return 0, err
	}
	if w == nil {
		w = &Watched{Day: o.day(), VideoID: c.VideoID}
	}
	added, merged := addAll(w.Intervals, c.Watched)
	if added <= 0 {
		return 0, nil
	}
	w.Intervals = merged
	w.ChargedSeconds += added
	w.LastAt = o.now
	// A client that couldn't read the title shouldn't wipe out one that could.
	if c.Title != "" {
		w.Title = c.Title
	}
	if c.Channel != "" {
		w.Channel = c.Channel
	}
	return added, o.r.SaveWatched(*w)
}

func validCheckin(c *Checkin) error {
	if !videoID.MatchString(c.VideoID) {
		return invalid("Not a video id.")
	}
	if len(c.Watched) > maxIntervals {
		return invalid("Too many stretches in one check-in.")
	}
	for _, iv := range c.Watched {
		if iv[0] < 0 || iv[1] < iv[0] || iv[1] > maxPosition {
			return invalid("Watched stretches must run forwards, within the video.")
		}
	}
	c.Title = text.Clip(c.Title, maxTitle)
	c.Channel = text.Clip(c.Channel, maxTitle)
	return nil
}

// Checkin charges what was watched, which was watched whatever the rules say now, and
// answers whether the video may keep playing until the expiry.
func (s *Service) Checkin(ctx context.Context, accountID int64, c Checkin) (CheckinResult, error) {
	if err := validCheckin(&c); err != nil {
		return CheckinResult{}, err
	}
	var result CheckinResult
	err := s.within(ctx, accountID, func(o *op) error {
		// Asked before this check-in is recorded, as the old server asked before each delivery.
		before, err := o.mayOpen(c.VideoID)
		if err != nil {
			return err
		}
		if _, err := o.charge(c); err != nil {
			return err
		}
		if before {
			if err := o.recordPlaying(c.VideoID); err != nil {
				return err
			}
		}
		after, err := o.mayOpen(c.VideoID)
		if err != nil {
			return err
		}
		result.Allowed = before && after
		result.ExpiresAt = o.now
		if result.Allowed {
			result.ExpiresAt = o.now.Add(o.cfg.CheckinExpiry)
		} else if ok, err := o.canWatch(); err != nil {
			return err
		} else if !ok {
			result.Reason = ReasonOutOfTime
		} else {
			result.Reason = ReasonNoSession
		}
		result.Status, err = o.status()
		return err
	})
	return result, err
}

// ---- reading state ----

func (o *op) status() (Status, error) {
	budget, err := o.budgetSeconds()
	if err != nil {
		return Status{}, err
	}
	used, err := o.r.UsedSeconds(o.day())
	if err != nil {
		return Status{}, err
	}
	state, err := o.state()
	if err != nil {
		return Status{}, err
	}
	s := Status{
		BudgetSeconds:    int64(budget),
		UsedSeconds:      int64(math.Round(used)),
		RemainingSeconds: max(0, int64(math.Round(budget-used))),
		CanWatch:         used < budget,
		ResetsAt:         o.resetsAt(),
		State:            state,
		SessionChoices:   make([]int, 0, MaxSessionVideos),
		SampleSeconds:    int(SampleDuration.Seconds()),
		CheckinSeconds:   int(o.cfg.CheckinEvery.Seconds()),
		Now:              o.now,
	}
	for n := 1; n <= MaxSessionVideos; n++ {
		s.SessionChoices = append(s.SessionChoices, n)
	}
	current, err := o.currentSession()
	if err != nil {
		return Status{}, err
	}
	if current != nil {
		k, sampled, err := o.sessionCounts(current.ID)
		if err != nil {
			return Status{}, err
		}
		s.Session = &SessionLeft{
			VideosLeft:  max(0, current.Videos-slotsUsed(k, sampled)),
			SamplesLeft: max(0, SamplesPerSession-sampled),
		}
	}
	if s.NextSessionAt, err = o.nextSessionAt(); err != nil {
		return Status{}, err
	}
	return s, nil
}

func (s *Service) Status(ctx context.Context, accountID int64) (Status, error) {
	var status Status
	err := s.within(ctx, accountID, func(o *op) (err error) {
		status, err = o.status()
		return err
	})
	return status, err
}
