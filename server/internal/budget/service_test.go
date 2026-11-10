package budget_test

// Ported from the Python server's tests/test_core.py, which is the spec for these rules.
// What changed is ADR 0001's: time is charged for what a client reports in a check-in,
// not for what a server delivers.
//
// Every test runs against the in-memory repository, and against Postgres too when
// ANTI_SOCIAL_TEST_DATABASE_URL is set, so the two can't drift apart.

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/rdmm404/anti-social/server/internal/accounts"
	accountspg "github.com/rdmm404/anti-social/server/internal/accounts/postgres"
	"github.com/rdmm404/anti-social/server/internal/apperr"
	"github.com/rdmm404/anti-social/server/internal/budget"
	"github.com/rdmm404/anti-social/server/internal/budget/memory"
	budgetpg "github.com/rdmm404/anti-social/server/internal/budget/postgres"
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
	t          *testing.T
	ctx        context.Context
	budget     *budget.Service
	clock      *clock
	account    int64
	newAccount func() int64
}

func config() budget.Config {
	return budget.Config{
		Location:      toronto,
		DayStartHour:  4,
		CoolingOff:    72 * time.Hour,
		CheckinExpiry: 60 * time.Second,
		CheckinEvery:  15 * time.Second,
		Seeds:         map[string]int{budget.DailyMinutes: 10, budget.SessionGapMinutes: 20},
	}
}

// forEach runs a test against each repository.
func forEach(t *testing.T, test func(t *testing.T, f *fixture)) {
	t.Parallel()
	t.Run("memory", func(t *testing.T) {
		t.Parallel()
		repo := memory.New()
		repo.AddAccount(1)
		next := int64(1)
		test(t, newFixture(t, repo, 1, func() int64 { next++; repo.AddAccount(next); return next }))
	})
	t.Run("postgres", func(t *testing.T) {
		t.Parallel()
		pool := testdb.Optional(t)
		ctx := context.Background()
		account, err := accounts.NewService(accountspg.New(pool)).EnsureOwner(ctx, map[string]string{"test": "token"})
		if err != nil {
			t.Fatal(err)
		}
		test(t, newFixture(t, budgetpg.New(pool), account, func() int64 { return insertAccount(t, pool) }))
	})
}

// insertAccount makes an account by hand, as there's no other way until pairing exists.
func insertAccount(t *testing.T, pool *pgxpool.Pool) int64 {
	var id int64
	if err := pool.QueryRow(context.Background(), "INSERT INTO accounts DEFAULT VALUES RETURNING id").Scan(&id); err != nil {
		t.Fatal(err)
	}
	return id
}

func newFixture(t *testing.T, repo budget.Repository, account int64, newAccount func() int64) *fixture {
	// Noon local time on an arbitrary day, so tests sit far from the 4am rollover.
	c := &clock{time.Date(2026, 6, 15, 12, 0, 0, 0, toronto)}
	svc := budget.NewService(repo, config(), func() time.Time { return c.now })
	return &fixture{t, context.Background(), svc, c, account, newAccount}
}

// id turns a short name into a well-formed video id: "a" is "a__________".
func id(name string) string { return name + strings.Repeat("_", 11-len(name)) }

func name(id string) string { return strings.TrimRight(id, "_") }

func (f *fixture) status() budget.Status {
	f.t.Helper()
	s, err := f.budget.Status(f.ctx, f.account)
	if err != nil {
		f.t.Fatal(err)
	}
	return s
}

func (f *fixture) checkin(video string, watched ...budget.Interval) budget.CheckinResult {
	f.t.Helper()
	r, err := f.budget.Checkin(f.ctx, f.account, budget.Checkin{VideoID: id(video), Title: "t", Channel: "c", Watched: watched})
	if err != nil {
		f.t.Fatal(err)
	}
	return r
}

func (f *fixture) startSession(videos int) {
	f.t.Helper()
	if _, err := f.budget.StartSession(f.ctx, f.account, videos); err != nil {
		f.t.Fatal(err)
	}
}

func (f *fixture) refusedSession(videos int) {
	f.t.Helper()
	_, err := f.budget.StartSession(f.ctx, f.account, videos)
	if _, ok := apperr.As(err); !ok {
		f.t.Fatalf("starting a %d-video session: got %v, want it turned down", videos, err)
	}
}

// watch plays a video for that many seconds of wall-clock time, checking in at the start
// and at the end the way a client would.
func (f *fixture) watch(video string, seconds time.Duration) {
	f.t.Helper()
	if r := f.checkin(video, budget.Interval{0, 1}); !r.Allowed {
		f.t.Fatalf("starting %s was refused (%s)", video, r.Reason)
	}
	if seconds == 0 {
		return
	}
	f.clock.advance(seconds)
	if r := f.checkin(video, budget.Interval{1, 2}); !r.Allowed {
		f.t.Fatalf("continuing %s was refused (%s)", video, r.Reason)
	}
}

func (f *fixture) allowed(video string) bool {
	f.t.Helper()
	return f.checkin(video).Allowed
}

func (f *fixture) settings() map[string]int {
	f.t.Helper()
	v, err := f.budget.Settings(f.ctx, f.account)
	if err != nil {
		f.t.Fatal(err)
	}
	return v.Values
}

func (f *fixture) change(changes map[string]int) budget.ChangeResult {
	f.t.Helper()
	r, err := f.budget.ChangeSettings(f.ctx, f.account, changes)
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

func TestCheckinsRejectNonsense(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		for _, c := range []budget.Checkin{
			{VideoID: "short"},
			{VideoID: id("v"), Watched: []budget.Interval{{10, 5}}},
			{VideoID: id("v"), Watched: []budget.Interval{{-1, 5}}},
			{VideoID: id("v"), Watched: []budget.Interval{{0, 49 * 3600}}},
			{VideoID: id("v"), Watched: make([]budget.Interval, 501)},
		} {
			if _, err := f.budget.Checkin(f.ctx, f.account, c); !apperr.Is(err, apperr.Invalid) {
				t.Fatalf("%+v: got %v, want it called invalid", c, err)
			}
		}
		equal(t, f.status().UsedSeconds, int64(0))
	})
}

// ---- charging ----

func TestAddIntervalCountsOnlyNewTime(t *testing.T) {
	added, merged := budget.AddInterval([]budget.Interval{{0, 10}, {20, 30}}, 5, 25)
	equal(t, added, 10.0)
	equal(t, merged, []budget.Interval{{0, 30}})
}

func TestReplaysAndRetriesChargeOnce(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		f.checkin("v", budget.Interval{0, 10})
		f.checkin("v", budget.Interval{0, 5})
		f.checkin("v", budget.Interval{5, 10}, budget.Interval{2, 3})
		equal(t, f.status().UsedSeconds, int64(10))
	})
}

func TestOneCheckinCanReportSeveralStretches(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		f.checkin("v", budget.Interval{30, 40}, budget.Interval{0, 10}, budget.Interval{5, 15})
		equal(t, f.status().UsedSeconds, int64(25))
	})
}

func TestWatchingPastTheBudgetIsChargedAndStops(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		r := f.checkin("v", budget.Interval{0, 610})
		equal(t, r.Allowed, false)
		equal(t, r.Reason, budget.ReasonOutOfTime)
		equal(t, r.Status.CanWatch, false)
		equal(t, r.Status.UsedSeconds, int64(610))
		equal(t, r.Status.RemainingSeconds, int64(0))
		equal(t, f.allowed("other"), false)
	})
}

func TestAnAllowedCheckinExpiresAfterAMinute(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		refused := f.checkin("v")
		equal(t, refused.Allowed, false)
		equal(t, refused.Reason, budget.ReasonNoSession)
		equal(t, refused.ExpiresAt, f.clock.now)
		f.startSession(1)
		r := f.checkin("v")
		equal(t, r.Allowed, true)
		equal(t, r.ExpiresAt, f.clock.now.Add(time.Minute))
		equal(t, r.Status.CheckinSeconds, 15)
	})
}

func TestBudgetResetsAtDayStartNotMidnight(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		f.checkin("v", budget.Interval{0, 600})
		f.clock.advance(13 * time.Hour) // 01:00 next calendar day: still the same budget day.
		equal(t, f.status().CanWatch, false)
		f.clock.advance(4 * time.Hour) // 05:00: new budget day.
		s := f.status()
		equal(t, s.CanWatch, true)
		equal(t, s.UsedSeconds, int64(0))
		equal(t, s.ResetsAt.Equal(time.Date(2026, 6, 17, 4, 0, 0, 0, toronto)), true)
	})
}

// ---- sessions ----

func TestNothingPlaysBeforeChoosing(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		equal(t, f.status().State, budget.StateChoose)
		equal(t, f.allowed("v"), false)
		f.startSession(1)
		equal(t, f.status().State, budget.StateOpen)
		equal(t, f.allowed("v"), true)
	})
}

func TestSessionSizeIsLimited(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		for _, videos := range []int{0, 4, -1} {
			f.refusedSession(videos)
		}
	})
}

func TestOnlyOneSessionAtATime(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		f.refusedSession(1)
	})
}

func TestSessionClosesAfterItsVideosAndTheKeptOneCanFinish(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		f.watch("a", 30*time.Second)
		s := f.status()
		equal(t, s.Session.VideosLeft, 0)
		equal(t, s.State, budget.StateGap)
		equal(t, f.allowed("b"), false)
		equal(t, f.allowed("a"), true)
	})
}

func TestGapCountsFromTheLastCheckin(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		f.watch("a", 30*time.Second)
		f.clock.advance(19 * time.Minute)
		f.checkin("a", budget.Interval{2, 3}) // Still watching, so the gap moves.
		f.clock.advance(19 * time.Minute)
		equal(t, f.status().State, budget.StateGap)
		f.refusedSession(1)
		f.clock.advance(61 * time.Second)
		equal(t, f.status().State, budget.StateChoose)
		f.startSession(1)
	})
}

func TestANewSessionNeedsNewSlots(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		f.watch("a", 30*time.Second)
		f.clock.advance(21 * time.Minute)
		f.startSession(1)
		f.watch("a", 0) // Kept already: free to resume.
		equal(t, f.status().Session.VideosLeft, 1)
		f.watch("b", 30*time.Second)
		equal(t, f.status().Session.VideosLeft, 0)
	})
}

func TestLeavingQuicklyGivesTheSlotBack(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		f.watch("a", 29*time.Second)
		s := f.status()
		equal(t, *s.Session, budget.SessionLeft{VideosLeft: 1, SamplesLeft: 2})
		equal(t, s.State, budget.StateOpen)
		f.watch("b", 30*time.Second)
		equal(t, *f.status().Session, budget.SessionLeft{VideosLeft: 0, SamplesLeft: 2})
	})
}

func TestOnlyThreeSamplesAreFree(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(2)
		for _, video := range []string{"a", "b", "c"} {
			f.watch(video, 5*time.Second)
		}
		equal(t, *f.status().Session, budget.SessionLeft{VideosLeft: 2, SamplesLeft: 0})
		f.watch("d", 5*time.Second) // Takes a slot straight away.
		equal(t, f.status().Session.VideosLeft, 1)
		f.watch("e", 5*time.Second)
		equal(t, f.status().State, budget.StateGap)
		equal(t, f.allowed("f"), false)
	})
}

func TestGoingBackToASampleNeedsAFreeSlot(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		f.watch("a", 5*time.Second)
		f.watch("b", 30*time.Second)
		equal(t, f.allowed("a"), false) // Past 30 seconds, and the slot went to b.
	})
}

func TestASampleThatKeepsPlayingTakesTheSlot(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		f.watch("a", 5*time.Second)
		f.clock.advance(60 * time.Second) // Paused, then resumed.
		equal(t, f.checkin("a", budget.Interval{2, 3}).Allowed, true)
		equal(t, f.status().Session.VideosLeft, 0)
	})
}

func TestUnusedVideosExpireWhenIdle(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(2)
		f.watch("a", 30*time.Second)
		f.clock.advance(30 * time.Minute)
		equal(t, f.status().State, budget.StateChoose)
		equal(t, f.allowed("b"), false)
		equal(t, f.allowed("a"), true) // Kept today, so it still resumes.
	})
}

func TestAnUntouchedSessionLeavesNoGap(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		f.clock.advance(30 * time.Minute)
		equal(t, f.status().State, budget.StateChoose)
		f.startSession(1)
	})
}

func TestTheNewDayNeedsANewSession(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		f.watch("a", 30*time.Second)
		f.clock.advance(24 * time.Hour)
		equal(t, f.status().State, budget.StateChoose)
		equal(t, f.allowed("a"), false)
	})
}

func TestTheDailyCapOutranksTheSession(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(3)
		f.checkin("a", budget.Interval{0, 600})
		equal(t, f.status().State, budget.StateNoTime)
		equal(t, f.allowed("a"), false)
		f.refusedSession(1)
	})
}

func TestARefusedCheckinIsStillChargedButTakesNoSlot(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		f.watch("a", 30*time.Second)
		// b plays on a device that hasn't heard the session is full yet.
		r := f.checkin("b", budget.Interval{0, 15})
		equal(t, r.Allowed, false)
		equal(t, r.Reason, budget.ReasonNoSession)
		equal(t, r.Status.UsedSeconds, int64(17))
		f.clock.advance(30 * time.Second)
		equal(t, f.allowed("b"), false)
	})
}

// ---- cooling off ----

func TestTighteningIsImmediate(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		equal(t, f.change(map[string]int{budget.DailyMinutes: 5}).Applied, []string{budget.DailyMinutes})
		equal(t, f.settings()[budget.DailyMinutes], 5)
	})
}

func TestLooseningWaits72Hours(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		equal(t, f.change(map[string]int{budget.DailyMinutes: 100}).Queued, []string{budget.DailyMinutes})
		f.clock.advance(71 * time.Hour)
		equal(t, f.settings()[budget.DailyMinutes], 10)
		f.clock.advance(2 * time.Hour)
		equal(t, f.settings()[budget.DailyMinutes], 100)
		v, _ := f.budget.Settings(f.ctx, f.account)
		equal(t, len(v.Pending), 0)
	})
}

func TestShorterGapCountsAsLoosening(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		equal(t, f.change(map[string]int{budget.SessionGapMinutes: 0}).Queued, []string{budget.SessionGapMinutes})
		equal(t, f.change(map[string]int{budget.SessionGapMinutes: 60}).Applied, []string{budget.SessionGapMinutes})
	})
}

func TestChangingAQueuedValueRestartsTheClock(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.change(map[string]int{budget.DailyMinutes: 100})
		f.clock.advance(71 * time.Hour)
		f.change(map[string]int{budget.DailyMinutes: 90})
		f.clock.advance(2 * time.Hour)
		equal(t, f.settings()[budget.DailyMinutes], 10)
	})
}

func TestResavingTheSameQueuedValueKeepsTheClock(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.change(map[string]int{budget.DailyMinutes: 100})
		f.clock.advance(71 * time.Hour)
		f.change(map[string]int{budget.DailyMinutes: 100})
		f.clock.advance(2 * time.Hour)
		equal(t, f.settings()[budget.DailyMinutes], 100)
	})
}

func TestCancelAndBounds(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.change(map[string]int{budget.DailyMinutes: 100})
		v, _ := f.budget.Settings(f.ctx, f.account)
		if err := f.budget.CancelChange(f.ctx, f.account, v.Pending[0].ID); err != nil {
			t.Fatal(err)
		}
		v, _ = f.budget.Settings(f.ctx, f.account)
		equal(t, len(v.Pending), 0)
		for _, bad := range []map[string]int{{budget.DailyMinutes: -1}, {"cooling_off_hours": 0}} {
			if _, err := f.budget.ChangeSettings(f.ctx, f.account, bad); !apperr.Is(err, apperr.Invalid) {
				t.Fatalf("%v: got %v, want it called invalid", bad, err)
			}
		}
	})
}

// ---- history ----

func TestHistoryFiltersByTextAndDate(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		f.budget.Checkin(f.ctx, f.account, budget.Checkin{VideoID: id("a"), Title: "Rust async", Channel: "Tokio", Watched: []budget.Interval{{0, 10}}})
		f.clock.advance(24 * time.Hour)
		f.startSession(1)
		f.budget.Checkin(f.ctx, f.account, budget.Checkin{VideoID: id("b"), Title: "Cooking 100% pasta", Channel: "Chef", Watched: []budget.Interval{{0, 10}}})

		ids := func(q budget.WatchedQuery) []string {
			t.Helper()
			if q.Limit == 0 {
				q.Limit = 50
			}
			videos, err := f.budget.SearchWatched(f.ctx, f.account, q)
			if err != nil {
				t.Fatal(err)
			}
			out := []string{}
			for _, v := range videos {
				out = append(out, name(v.VideoID))
			}
			return out
		}
		dayTwo := "2026-06-16"
		equal(t, ids(budget.WatchedQuery{}), []string{"b", "a"})
		equal(t, ids(budget.WatchedQuery{Text: "rust"}), []string{"a"})
		equal(t, ids(budget.WatchedQuery{Text: "tokio"}), []string{"a"})
		equal(t, ids(budget.WatchedQuery{Text: "100%"}), []string{"b"})
		equal(t, ids(budget.WatchedQuery{Text: "%"}), []string{"b"}) // A literal percent sign, not a wildcard.
		equal(t, ids(budget.WatchedQuery{StartDay: dayTwo}), []string{"b"})
		equal(t, ids(budget.WatchedQuery{EndDay: dayTwo}), []string{"b", "a"})
		equal(t, ids(budget.WatchedQuery{Limit: 1, Offset: 1}), []string{"a"})

		h, err := f.budget.History(f.ctx, f.account, 2)
		if err != nil {
			t.Fatal(err)
		}
		equal(t, h.Days, []budget.DayTotal{{"2026-06-16", 10}, {"2026-06-15", 10}})
		equal(t, len(h.Today), 1)
	})
}

// ---- accounts ----

func TestAccountsDontShareABudget(t *testing.T) {
	forEach(t, func(t *testing.T, f *fixture) {
		f.startSession(1)
		f.checkin("v", budget.Interval{0, 100})
		// Accounts can only be made by hand until pairing exists.
		other := f.newAccount()
		s, err := f.budget.Status(f.ctx, other)
		if err != nil {
			t.Fatal(err)
		}
		equal(t, s.UsedSeconds, int64(0))
		equal(t, s.State, budget.StateChoose)
		equal(t, f.status().UsedSeconds, int64(100))
		if _, err := f.budget.Status(f.ctx, 999); !errors.Is(err, budget.ErrNoAccount) {
			t.Fatalf("got %v, want ErrNoAccount", err)
		}
	})
}
