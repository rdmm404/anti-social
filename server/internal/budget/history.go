package budget

import (
	"context"
	"math"
	"regexp"
	"time"

	"github.com/rdmm404/anti-social/server/internal/text"
)

var dayPattern = regexp.MustCompile(`^\d{4}-\d{2}-\d{2}$`)

// History is the last few budget days' totals, newest first, and today's videos.
func (s *Service) History(ctx context.Context, accountID int64, days int) (History, error) {
	today := s.cfg.dayOf(s.clock())
	start := today.AddDate(0, 0, -(days - 1)).Format(time.DateOnly)
	end := today.Format(time.DateOnly)
	totals, err := s.repo.DayTotals(ctx, accountID, start, end)
	if err != nil {
		return History{}, err
	}
	h := History{Days: make([]DayTotal, 0, days)}
	for offset := range days {
		day := today.AddDate(0, 0, -offset).Format(time.DateOnly)
		h.Days = append(h.Days, DayTotal{day, int64(math.Round(totals[day]))})
	}
	h.Today, err = s.repo.SearchWatched(ctx, accountID, WatchedQuery{StartDay: end, EndDay: end, Limit: 1000})
	return h, err
}

// SearchWatched finds watched videos, newest first. A video appears once per day watched.
func (s *Service) SearchWatched(ctx context.Context, accountID int64, q WatchedQuery) ([]WatchedVideo, error) {
	for _, d := range []string{q.StartDay, q.EndDay} {
		if d != "" && !dayPattern.MatchString(d) {
			return nil, invalid("Dates must look like 2026-11-03.")
		}
	}
	q.Text = text.Clip(q.Text, maxTitle)
	q.Offset = max(0, q.Offset)
	return s.repo.SearchWatched(ctx, accountID, q)
}
