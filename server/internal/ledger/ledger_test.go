package ledger_test

// Ported from the Python server's tests/test_core.py, which is the spec for these rules.
// What changed is ADR 0001's: time is charged for what a client reports in a check-in,
// not for what a server delivers.

import (
	"context"
	"errors"
	"reflect"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/rdmm404/anti-social/server/internal/accounts"
	"github.com/rdmm404/anti-social/server/internal/ledger"
	"github.com/rdmm404/anti-social/server/internal/testdb"
)

var toronto = func() *time.Location {
	loc, err := time.LoadLocation("America/Toronto")
	if err != nil {
		panic(err)
	}
	return loc
}()

type clock struct{ now time.Time }

func (c *clock) advance(d time.Duration) { c.now = c.now.Add(d) }

type fixture struct {
	t       *testing.T
	ctx     context.Context
	pool    *pgxpool.Pool
	ledger  *ledger.Ledger
	clock   *clock
	account int64
}

func config() ledger.Config {
	return ledger.Config{
		Location:      toronto,
		DayStartHour:  4,
		CoolingOff:    72 * time.Hour,
		CheckinExpiry: 60 * time.Second,
		CheckinEvery:  15 * time.Second,
		Seeds:         map[string]int{ledger.DailyMinutes: 10, ledger.SessionGapMinutes: 20},
	}
}

func setup(t *testing.T) *fixture {
	t.Helper()
	t.Parallel()
	pool := testdb.New(t)
	ctx := context.Background()
	account, err := accounts.New(pool).EnsureOwner(ctx, map[string]string{"test": "token"})
	if err != nil {
		t.Fatal(err)
	}
	// Noon local time on an arbitrary day, so tests sit far from the 4am rollover.
	c := &clock{time.Date(2026, 6, 15, 12, 0, 0, 0, toronto)}
	return &fixture{t, ctx, pool, ledger.New(pool, config(), func() time.Time { return c.now }), c, account}
}

func (f *fixture) status() ledger.Status {
	f.t.Helper()
	s, err := f.ledger.Status(f.ctx, f.account)
	if err != nil {
		f.t.Fatal(err)
	}
	return s
}

func (f *fixture) checkin(video string, watched ...ledger.Interval) ledger.CheckinResult {
	f.t.Helper()
	r, err := f.ledger.Checkin(f.ctx, f.account, ledger.Checkin{VideoID: video, Title: "t", Channel: "c", Watched: watched})
	if err != nil {
		f.t.Fatal(err)
	}
	return r
}

func (f *fixture) startSession(videos int) {
	f.t.Helper()
	if _, err := f.ledger.StartSession(f.ctx, f.account, videos); err != nil {
		f.t.Fatal(err)
	}
}

func (f *fixture) refusedSession(videos int) {
	f.t.Helper()
	_, err := f.ledger.StartSession(f.ctx, f.account, videos)
	var refusal *ledger.Refusal
	if !errors.As(err, &refusal) {
		f.t.Fatalf("starting a %d-video session: got %v, want a refusal", videos, err)
	}
}

// watch plays a video for that many seconds of wall-clock time, checking in at the start
// and at the end the way a client would.
func (f *fixture) watch(video string, seconds time.Duration) {
	f.t.Helper()
	if r := f.checkin(video, ledger.Interval{0, 1}); !r.Allowed {
		f.t.Fatalf("starting %s was refused (%s)", video, r.Reason)
	}
	if seconds == 0 {
		return
	}
	f.clock.advance(seconds)
	if r := f.checkin(video, ledger.Interval{1, 2}); !r.Allowed {
		f.t.Fatalf("continuing %s was refused (%s)", video, r.Reason)
	}
}

func (f *fixture) allowed(video string) bool {
	f.t.Helper()
	return f.checkin(video).Allowed
}

func (f *fixture) settings() map[string]int {
	f.t.Helper()
	v, err := f.ledger.Settings(f.ctx, f.account)
	if err != nil {
		f.t.Fatal(err)
	}
	return v.Settings
}

func (f *fixture) change(changes map[string]int) ledger.ChangeResult {
	f.t.Helper()
	r, err := f.ledger.ChangeSettings(f.ctx, f.account, changes)
	if err != nil {
		f.t.Fatal(err)
	}
	return r
}

func equal[T any](t *testing.T, got, want T) {
	t.Helper()
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v, want %+v", got, want)
	}
}

// ---- charging ----

func TestAddIntervalCountsOnlyNewTime(t *testing.T) {
	added, merged := ledger.AddInterval([]ledger.Interval{{0, 10}, {20, 30}}, 5, 25)
	equal(t, added, 10.0)
	equal(t, merged, []ledger.Interval{{0, 30}})
}

func TestReplaysAndRetriesChargeOnce(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	f.checkin("v", ledger.Interval{0, 10})
	f.checkin("v", ledger.Interval{0, 5})
	f.checkin("v", ledger.Interval{5, 10}, ledger.Interval{2, 3})
	equal(t, f.status().UsedSeconds, int64(10))
}

func TestOneCheckinCanReportSeveralStretches(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	f.checkin("v", ledger.Interval{30, 40}, ledger.Interval{0, 10}, ledger.Interval{5, 15})
	equal(t, f.status().UsedSeconds, int64(25))
}

func TestWatchingPastTheBudgetIsChargedAndStops(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	r := f.checkin("v", ledger.Interval{0, 610})
	equal(t, r.Allowed, false)
	equal(t, r.Reason, ledger.ReasonOutOfTime)
	equal(t, r.Status.CanWatch, false)
	equal(t, r.Status.UsedSeconds, int64(610))
	equal(t, r.Status.RemainingSeconds, int64(0))
	equal(t, f.allowed("other"), false)
}

func TestAnAllowedCheckinExpiresAfterAMinute(t *testing.T) {
	f := setup(t)
	refused := f.checkin("v")
	equal(t, refused.Allowed, false)
	equal(t, refused.Reason, ledger.ReasonNoSession)
	equal(t, refused.ExpiresAt, f.clock.now)
	f.startSession(1)
	r := f.checkin("v")
	equal(t, r.Allowed, true)
	equal(t, r.ExpiresAt, f.clock.now.Add(time.Minute))
	equal(t, r.Status.CheckinSeconds, 15)
}

func TestBudgetResetsAtDayStartNotMidnight(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	f.checkin("v", ledger.Interval{0, 600})
	f.clock.advance(13 * time.Hour) // 01:00 next calendar day: still the same budget day.
	equal(t, f.status().CanWatch, false)
	f.clock.advance(4 * time.Hour) // 05:00: new budget day.
	s := f.status()
	equal(t, s.CanWatch, true)
	equal(t, s.UsedSeconds, int64(0))
	equal(t, s.ResetsAt, float64(time.Date(2026, 6, 17, 4, 0, 0, 0, toronto).Unix()))
}

// ---- sessions ----

func TestNothingPlaysBeforeChoosing(t *testing.T) {
	f := setup(t)
	equal(t, f.status().State, ledger.StateChoose)
	equal(t, f.allowed("v"), false)
	f.startSession(1)
	equal(t, f.status().State, ledger.StateOpen)
	equal(t, f.allowed("v"), true)
}

func TestSessionSizeIsLimited(t *testing.T) {
	f := setup(t)
	for _, videos := range []int{0, 4, -1} {
		f.refusedSession(videos)
	}
}

func TestOnlyOneSessionAtATime(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	f.refusedSession(1)
}

func TestSessionClosesAfterItsVideosAndTheKeptOneCanFinish(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	f.watch("a", 30*time.Second)
	s := f.status()
	equal(t, s.Session.VideosLeft, 0)
	equal(t, s.State, ledger.StateGap)
	equal(t, f.allowed("b"), false)
	equal(t, f.allowed("a"), true)
}

func TestGapCountsFromTheLastCheckin(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	f.watch("a", 30*time.Second)
	f.clock.advance(19 * time.Minute)
	f.checkin("a", ledger.Interval{2, 3}) // Still watching, so the gap moves.
	f.clock.advance(19 * time.Minute)
	equal(t, f.status().State, ledger.StateGap)
	f.refusedSession(1)
	f.clock.advance(61 * time.Second)
	equal(t, f.status().State, ledger.StateChoose)
	f.startSession(1)
}

func TestANewSessionNeedsNewSlots(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	f.watch("a", 30*time.Second)
	f.clock.advance(21 * time.Minute)
	f.startSession(1)
	f.watch("a", 0) // Kept already: free to resume.
	equal(t, f.status().Session.VideosLeft, 1)
	f.watch("b", 30*time.Second)
	equal(t, f.status().Session.VideosLeft, 0)
}

func TestLeavingQuicklyGivesTheSlotBack(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	f.watch("a", 29*time.Second)
	s := f.status()
	equal(t, *s.Session, ledger.SessionLeft{VideosLeft: 1, SamplesLeft: 2})
	equal(t, s.State, ledger.StateOpen)
	f.watch("b", 30*time.Second)
	equal(t, *f.status().Session, ledger.SessionLeft{VideosLeft: 0, SamplesLeft: 2})
}

func TestOnlyThreeSamplesAreFree(t *testing.T) {
	f := setup(t)
	f.startSession(2)
	for _, video := range []string{"a", "b", "c"} {
		f.watch(video, 5*time.Second)
	}
	equal(t, *f.status().Session, ledger.SessionLeft{VideosLeft: 2, SamplesLeft: 0})
	f.watch("d", 5*time.Second) // Takes a slot straight away.
	equal(t, f.status().Session.VideosLeft, 1)
	f.watch("e", 5*time.Second)
	equal(t, f.status().State, ledger.StateGap)
	equal(t, f.allowed("f"), false)
}

func TestGoingBackToASampleNeedsAFreeSlot(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	f.watch("a", 5*time.Second)
	f.watch("b", 30*time.Second)
	equal(t, f.allowed("a"), false) // Past 30 seconds, and the slot went to b.
}

func TestASampleThatKeepsPlayingTakesTheSlot(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	f.watch("a", 5*time.Second)
	f.clock.advance(60 * time.Second) // Paused, then resumed.
	equal(t, f.checkin("a", ledger.Interval{2, 3}).Allowed, true)
	equal(t, f.status().Session.VideosLeft, 0)
}

func TestUnusedVideosExpireWhenIdle(t *testing.T) {
	f := setup(t)
	f.startSession(2)
	f.watch("a", 30*time.Second)
	f.clock.advance(30 * time.Minute)
	equal(t, f.status().State, ledger.StateChoose)
	equal(t, f.allowed("b"), false)
	equal(t, f.allowed("a"), true) // Kept today, so it still resumes.
}

func TestAnUntouchedSessionLeavesNoGap(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	f.clock.advance(30 * time.Minute)
	equal(t, f.status().State, ledger.StateChoose)
	f.startSession(1)
}

func TestTheNewDayNeedsANewSession(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	f.watch("a", 30*time.Second)
	f.clock.advance(24 * time.Hour)
	equal(t, f.status().State, ledger.StateChoose)
	equal(t, f.allowed("a"), false)
}

func TestTheDailyCapOutranksTheSession(t *testing.T) {
	f := setup(t)
	f.startSession(3)
	f.checkin("a", ledger.Interval{0, 600})
	equal(t, f.status().State, ledger.StateNoTime)
	equal(t, f.allowed("a"), false)
	f.refusedSession(1)
}

func TestARefusedCheckinIsStillChargedButTakesNoSlot(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	f.watch("a", 30*time.Second)
	// b plays on a device that hasn't heard the session is full yet.
	r := f.checkin("b", ledger.Interval{0, 15})
	equal(t, r.Allowed, false)
	equal(t, r.Reason, ledger.ReasonNoSession)
	equal(t, r.Status.UsedSeconds, int64(17))
	f.clock.advance(30 * time.Second)
	equal(t, f.allowed("b"), false)
}

// ---- cooling off ----

func TestTighteningIsImmediate(t *testing.T) {
	f := setup(t)
	equal(t, f.change(map[string]int{ledger.DailyMinutes: 5}).Applied, []string{ledger.DailyMinutes})
	equal(t, f.settings()[ledger.DailyMinutes], 5)
}

func TestLooseningWaits72Hours(t *testing.T) {
	f := setup(t)
	equal(t, f.change(map[string]int{ledger.DailyMinutes: 100}).Queued, []string{ledger.DailyMinutes})
	f.clock.advance(71 * time.Hour)
	equal(t, f.settings()[ledger.DailyMinutes], 10)
	f.clock.advance(2 * time.Hour)
	equal(t, f.settings()[ledger.DailyMinutes], 100)
	v, _ := f.ledger.Settings(f.ctx, f.account)
	equal(t, v.Pending, []ledger.PendingChange{})
}

func TestShorterGapCountsAsLoosening(t *testing.T) {
	f := setup(t)
	equal(t, f.change(map[string]int{ledger.SessionGapMinutes: 0}).Queued, []string{ledger.SessionGapMinutes})
	equal(t, f.change(map[string]int{ledger.SessionGapMinutes: 60}).Applied, []string{ledger.SessionGapMinutes})
}

func TestChangingAQueuedValueRestartsTheClock(t *testing.T) {
	f := setup(t)
	f.change(map[string]int{ledger.DailyMinutes: 100})
	f.clock.advance(71 * time.Hour)
	f.change(map[string]int{ledger.DailyMinutes: 90})
	f.clock.advance(2 * time.Hour)
	equal(t, f.settings()[ledger.DailyMinutes], 10)
}

func TestResavingTheSameQueuedValueKeepsTheClock(t *testing.T) {
	f := setup(t)
	f.change(map[string]int{ledger.DailyMinutes: 100})
	f.clock.advance(71 * time.Hour)
	f.change(map[string]int{ledger.DailyMinutes: 100})
	f.clock.advance(2 * time.Hour)
	equal(t, f.settings()[ledger.DailyMinutes], 100)
}

func TestCancelAndBounds(t *testing.T) {
	f := setup(t)
	f.change(map[string]int{ledger.DailyMinutes: 100})
	v, _ := f.ledger.Settings(f.ctx, f.account)
	if err := f.ledger.CancelChange(f.ctx, f.account, v.Pending[0].ID); err != nil {
		t.Fatal(err)
	}
	v, _ = f.ledger.Settings(f.ctx, f.account)
	equal(t, v.Pending, []ledger.PendingChange{})
	for _, bad := range []map[string]int{{ledger.DailyMinutes: -1}, {"cooling_off_hours": 0}} {
		var refusal *ledger.Refusal
		if _, err := f.ledger.ChangeSettings(f.ctx, f.account, bad); !errors.As(err, &refusal) {
			t.Fatalf("%v: got %v, want a refusal", bad, err)
		}
	}
}

// ---- history ----

func TestHistoryFiltersByTextAndDate(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	f.ledger.Checkin(f.ctx, f.account, ledger.Checkin{VideoID: "a", Title: "Rust async", Channel: "Tokio", Watched: []ledger.Interval{{0, 10}}})
	f.clock.advance(24 * time.Hour)
	f.startSession(1)
	f.ledger.Checkin(f.ctx, f.account, ledger.Checkin{VideoID: "b", Title: "Cooking 100% pasta", Channel: "Chef", Watched: []ledger.Interval{{0, 10}}})

	ids := func(q ledger.WatchedQuery) []string {
		t.Helper()
		if q.Limit == 0 {
			q.Limit = 50
		}
		videos, err := f.ledger.SearchWatched(f.ctx, f.account, q)
		if err != nil {
			t.Fatal(err)
		}
		out := []string{}
		for _, v := range videos {
			out = append(out, v.VideoID)
		}
		return out
	}
	dayTwo := "2026-06-16"
	equal(t, ids(ledger.WatchedQuery{}), []string{"b", "a"})
	equal(t, ids(ledger.WatchedQuery{Text: "rust"}), []string{"a"})
	equal(t, ids(ledger.WatchedQuery{Text: "tokio"}), []string{"a"})
	equal(t, ids(ledger.WatchedQuery{Text: "100%"}), []string{"b"})
	equal(t, ids(ledger.WatchedQuery{Text: "%"}), []string{"b"}) // A literal percent sign, not a wildcard.
	equal(t, ids(ledger.WatchedQuery{StartDay: dayTwo}), []string{"b"})
	equal(t, ids(ledger.WatchedQuery{EndDay: dayTwo}), []string{"b", "a"})
	equal(t, ids(ledger.WatchedQuery{Limit: 1, Offset: 1}), []string{"a"})

	h, err := f.ledger.History(f.ctx, f.account, 2)
	if err != nil {
		t.Fatal(err)
	}
	equal(t, h.Days, []ledger.DayTotal{{"2026-06-16", 10}, {"2026-06-15", 10}})
	equal(t, len(h.Today), 1)
}

// ---- accounts ----

func TestAccountsDontShareABudget(t *testing.T) {
	f := setup(t)
	f.startSession(1)
	f.checkin("v", ledger.Interval{0, 100})
	// Accounts can only be made by hand until pairing exists.
	var other int64
	if err := f.pool.QueryRow(f.ctx, "INSERT INTO accounts DEFAULT VALUES RETURNING id").Scan(&other); err != nil {
		t.Fatal(err)
	}
	s, err := f.ledger.Status(f.ctx, other)
	if err != nil {
		t.Fatal(err)
	}
	equal(t, s.UsedSeconds, int64(0))
	equal(t, s.State, ledger.StateChoose)
	equal(t, f.status().UsedSeconds, int64(100))
	if _, err := f.ledger.Status(f.ctx, 999); !errors.Is(err, ledger.ErrNoAccount) {
		t.Fatalf("got %v, want ErrNoAccount", err)
	}
}
