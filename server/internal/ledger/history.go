package ledger

import (
	"context"
	"fmt"
	"math"
	"strings"
	"time"
)

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

type History struct {
	Days  []DayTotal     `json:"days"`
	Today []WatchedVideo `json:"today"`
}

// History is the last few budget days' totals, newest first, and today's videos.
func (l *Ledger) History(ctx context.Context, accountID int64, days int) (History, error) {
	var h History
	err := l.within(ctx, accountID, func(a *account) error {
		today := a.dayOf(a.now)
		h.Days = make([]DayTotal, 0, days)
		for offset := range days {
			day := today.AddDate(0, 0, -offset).Format(time.DateOnly)
			var watched float64
			if err := a.tx.QueryRow(a.ctx,
				"SELECT COALESCE(SUM(charged_seconds), 0) FROM watched WHERE account_id = $1 AND day = $2",
				a.id, day,
			).Scan(&watched); err != nil {
				return err
			}
			h.Days = append(h.Days, DayTotal{day, int64(math.Round(watched))})
		}
		var err error
		h.Today, err = a.searchWatched(WatchedQuery{StartDay: a.day(), EndDay: a.day(), Limit: 1000})
		return err
	})
	return h, err
}

type WatchedQuery struct {
	Text     string
	StartDay string // inclusive, YYYY-MM-DD, empty for no bound
	EndDay   string
	Limit    int
	Offset   int
}

// SearchWatched finds watched videos, newest first. A video appears once per day watched.
func (l *Ledger) SearchWatched(ctx context.Context, accountID int64, q WatchedQuery) ([]WatchedVideo, error) {
	var videos []WatchedVideo
	err := l.within(ctx, accountID, func(a *account) (err error) {
		videos, err = a.searchWatched(q)
		return err
	})
	return videos, err
}

func (a *account) searchWatched(q WatchedQuery) ([]WatchedVideo, error) {
	where := []string{"account_id = $1"}
	args := []any{a.id}
	arg := func(v any) string {
		args = append(args, v)
		return fmt.Sprintf("$%d", len(args))
	}
	if text := strings.TrimSpace(q.Text); text != "" {
		escaped := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(text)
		p := arg("%" + escaped + "%")
		where = append(where, fmt.Sprintf(`(title ILIKE %s OR channel ILIKE %s)`, p, p))
	}
	if q.StartDay != "" {
		where = append(where, "day >= "+arg(q.StartDay))
	}
	if q.EndDay != "" {
		where = append(where, "day <= "+arg(q.EndDay))
	}
	sql := fmt.Sprintf(`
		SELECT day::text, video_id, title, channel, charged_seconds FROM watched
		WHERE %s ORDER BY day DESC, last_at DESC LIMIT %s OFFSET %s`,
		strings.Join(where, " AND "), arg(q.Limit), arg(max(0, q.Offset)))
	rows, err := a.tx.Query(a.ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	videos := []WatchedVideo{}
	for rows.Next() {
		var v WatchedVideo
		if err := rows.Scan(&v.Day, &v.VideoID, &v.Title, &v.Channel, &v.WatchedSeconds); err != nil {
			return nil, err
		}
		videos = append(videos, v)
	}
	return videos, rows.Err()
}
