// Package postgres keeps budgets in Postgres. Within holds an account by locking its row for
// the length of a transaction.
package postgres

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/rdmm404/anti-social/server/internal/budget"
)

type Repository struct{ pool *pgxpool.Pool }

var _ budget.Repository = (*Repository)(nil)

func New(pool *pgxpool.Pool) *Repository { return &Repository{pool} }

func (r *Repository) Within(ctx context.Context, accountID int64, fn func(budget.Records) error) error {
	return pgx.BeginFunc(ctx, r.pool, func(tx pgx.Tx) error {
		// Another Within for this account waits here until this transaction ends.
		tag, err := tx.Exec(ctx, "SELECT 1 FROM accounts WHERE id = $1 FOR UPDATE", accountID)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return budget.ErrNoAccount
		}
		return fn(&records{ctx: ctx, tx: tx, account: accountID})
	})
}

func (r *Repository) DayTotals(ctx context.Context, accountID int64, start, end string) (map[string]float64, error) {
	rows, err := r.pool.Query(ctx, `
		SELECT day::text, SUM(charged_seconds) FROM watched
		WHERE account_id = $1 AND day BETWEEN $2 AND $3 GROUP BY day`,
		accountID, start, end)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	totals := map[string]float64{}
	for rows.Next() {
		var day string
		var seconds float64
		if err := rows.Scan(&day, &seconds); err != nil {
			return nil, err
		}
		totals[day] = seconds
	}
	return totals, rows.Err()
}

func (r *Repository) SearchWatched(ctx context.Context, accountID int64, q budget.WatchedQuery) ([]budget.WatchedVideo, error) {
	where := []string{"account_id = $1"}
	args := []any{accountID}
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
		strings.Join(where, " AND "), arg(q.Limit), arg(q.Offset))
	rows, err := r.pool.Query(ctx, sql, args...)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByPos[budget.WatchedVideo])
}

// records is one account's budget inside a Within transaction.
type records struct {
	ctx     context.Context
	tx      pgx.Tx
	account int64
}

func (r *records) SavedSettings() (map[string]int, error) {
	rows, err := r.tx.Query(r.ctx, "SELECT key, value FROM settings WHERE account_id = $1", r.account)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	values := map[string]int{}
	for rows.Next() {
		var key string
		var value int
		if err := rows.Scan(&key, &value); err != nil {
			return nil, err
		}
		values[key] = value
	}
	return values, rows.Err()
}

func (r *records) SaveSetting(key string, value int) error {
	_, err := r.tx.Exec(r.ctx, `
		INSERT INTO settings (account_id, key, value) VALUES ($1, $2, $3)
		ON CONFLICT (account_id, key) DO UPDATE SET value = excluded.value`,
		r.account, key, value)
	return err
}

func (r *records) PendingChanges() ([]budget.PendingChange, error) {
	rows, err := r.tx.Query(r.ctx, `
		SELECT id, key, value, requested_at, apply_at FROM pending_changes
		WHERE account_id = $1 ORDER BY apply_at, id`, r.account)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByPos[budget.PendingChange])
}

func (r *records) QueueChange(c budget.PendingChange) error {
	_, err := r.tx.Exec(r.ctx, `
		INSERT INTO pending_changes (account_id, key, value, requested_at, apply_at)
		VALUES ($1, $2, $3, $4, $5)`,
		r.account, c.Key, c.Value, c.RequestedAt, c.ApplyAt)
	return err
}

func (r *records) DeletePendingChange(id int64) error {
	_, err := r.tx.Exec(r.ctx, "DELETE FROM pending_changes WHERE account_id = $1 AND id = $2", r.account, id)
	return err
}

func (r *records) LatestSession(day string) (*budget.Session, error) {
	s := budget.Session{Day: day}
	err := r.tx.QueryRow(r.ctx, `
		SELECT s.id, s.videos, s.started_at, MAX(v.last_at)
		FROM sessions s LEFT JOIN session_videos v ON v.session_id = s.id
		WHERE s.account_id = $1 AND s.day = $2
		GROUP BY s.id ORDER BY s.id DESC LIMIT 1`,
		r.account, day,
	).Scan(&s.ID, &s.Videos, &s.StartedAt, &s.LastPlayedAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &s, nil
}

func (r *records) CreateSession(s budget.Session) error {
	_, err := r.tx.Exec(r.ctx,
		"INSERT INTO sessions (account_id, day, videos, started_at) VALUES ($1, $2, $3, $4)",
		r.account, s.Day, s.Videos, s.StartedAt)
	return err
}

func (r *records) SessionVideos(sessionID int64) ([]budget.SessionVideo, error) {
	// Joined to sessions so another account's session id finds nothing.
	rows, err := r.tx.Query(r.ctx, `
		SELECT v.session_id, v.video_id, v.first_at, v.last_at
		FROM session_videos v JOIN sessions s ON s.id = v.session_id
		WHERE s.account_id = $1 AND v.session_id = $2`, r.account, sessionID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByPos[budget.SessionVideo])
}

func (r *records) Plays(day, videoID string) ([]budget.SessionVideo, error) {
	rows, err := r.tx.Query(r.ctx, `
		SELECT v.session_id, v.video_id, v.first_at, v.last_at
		FROM session_videos v JOIN sessions s ON s.id = v.session_id
		WHERE s.account_id = $1 AND s.day = $2 AND v.video_id = $3
		ORDER BY v.session_id DESC`, r.account, day, videoID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByPos[budget.SessionVideo])
}

func (r *records) SavePlay(sessionID int64, videoID string, at time.Time) error {
	_, err := r.tx.Exec(r.ctx, `
		INSERT INTO session_videos (session_id, video_id, first_at, last_at)
		SELECT id, $3, $4, $4 FROM sessions WHERE account_id = $1 AND id = $2
		ON CONFLICT (session_id, video_id) DO UPDATE SET last_at = excluded.last_at`,
		r.account, sessionID, videoID, at)
	return err
}

func (r *records) LastPlayedAt() (*time.Time, error) {
	var last *time.Time
	err := r.tx.QueryRow(r.ctx, `
		SELECT MAX(v.last_at) FROM session_videos v JOIN sessions s ON s.id = v.session_id
		WHERE s.account_id = $1`, r.account,
	).Scan(&last)
	return last, err
}

func (r *records) UsedSeconds(day string) (float64, error) {
	var used float64
	err := r.tx.QueryRow(r.ctx,
		"SELECT COALESCE(SUM(charged_seconds), 0) FROM watched WHERE account_id = $1 AND day = $2",
		r.account, day,
	).Scan(&used)
	return used, err
}

func (r *records) Watched(day, videoID string) (*budget.Watched, error) {
	w := budget.Watched{Day: day, VideoID: videoID}
	err := r.tx.QueryRow(r.ctx, `
		SELECT title, channel, intervals, charged_seconds, last_at FROM watched
		WHERE account_id = $1 AND day = $2 AND video_id = $3`,
		r.account, day, videoID,
	).Scan(&w.Title, &w.Channel, &w.Intervals, &w.ChargedSeconds, &w.LastAt)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &w, nil
}

func (r *records) SaveWatched(w budget.Watched) error {
	_, err := r.tx.Exec(r.ctx, `
		INSERT INTO watched (account_id, day, video_id, title, channel, intervals, charged_seconds, last_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		ON CONFLICT (account_id, day, video_id) DO UPDATE SET
			title = excluded.title, channel = excluded.channel, intervals = excluded.intervals,
			charged_seconds = excluded.charged_seconds, last_at = excluded.last_at`,
		r.account, w.Day, w.VideoID, w.Title, w.Channel, w.Intervals, w.ChargedSeconds, w.LastAt)
	return err
}
