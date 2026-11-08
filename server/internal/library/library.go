// Package library keeps an account's subscriptions and playlists. Reading them never needs
// YouTube: clients fetch what a channel or video holds themselves.
package library

import (
	"context"
	"errors"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const (
	MaxName  = 100
	MaxTitle = 200
)

// Problem is a request that can't be done as asked. Its message is safe to show.
type Problem struct{ Message string }

func (p *Problem) Error() string { return p.Message }

var ErrNotFound = errors.New("not found")

type Library struct {
	pool  *pgxpool.Pool
	clock func() time.Time
}

func New(pool *pgxpool.Pool, clock func() time.Time) *Library {
	if clock == nil {
		clock = time.Now
	}
	return &Library{pool: pool, clock: clock}
}

// clip trims and shortens text to at most n characters.
func clip(s string, n int) string {
	s = strings.TrimSpace(s)
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n])
}

// ---- subscriptions ----

type Subscription struct {
	ChannelID string `json:"channel_id"`
	Name      string `json:"name"`
}

func (l *Library) Subscriptions(ctx context.Context, accountID int64) ([]Subscription, error) {
	rows, err := l.pool.Query(ctx,
		"SELECT channel_id, name FROM subscriptions WHERE account_id = $1 ORDER BY lower(name), channel_id", accountID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByPos[Subscription])
}

func (l *Library) Subscribe(ctx context.Context, accountID int64, channelID, name string) error {
	_, err := l.pool.Exec(ctx, `
		INSERT INTO subscriptions (account_id, channel_id, name, added_at) VALUES ($1, $2, $3, $4)
		ON CONFLICT (account_id, channel_id) DO UPDATE SET name = excluded.name`,
		accountID, channelID, clip(name, MaxTitle), l.clock())
	return err
}

func (l *Library) Unsubscribe(ctx context.Context, accountID int64, channelID string) error {
	_, err := l.pool.Exec(ctx,
		"DELETE FROM subscriptions WHERE account_id = $1 AND channel_id = $2", accountID, channelID)
	return err
}

// ---- playlists ----

type PlaylistRef struct {
	ID    int64  `json:"id"`
	Name  string `json:"name"`
	Count int    `json:"count"`
}

type Item struct {
	ID       string `json:"id"`
	Title    string `json:"title"`
	Channel  string `json:"channel"`
	Duration *int   `json:"duration"`
}

type Playlist struct {
	ID    int64  `json:"id"`
	Name  string `json:"name"`
	Items []Item `json:"items"`
}

func (l *Library) Playlists(ctx context.Context, accountID int64) ([]PlaylistRef, error) {
	rows, err := l.pool.Query(ctx, `
		SELECT p.id, p.name, COUNT(i.video_id)::int FROM playlists p
		LEFT JOIN playlist_items i ON i.playlist_id = p.id
		WHERE p.account_id = $1 GROUP BY p.id ORDER BY lower(p.name), p.id`, accountID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByPos[PlaylistRef])
}

func (l *Library) CreatePlaylist(ctx context.Context, accountID int64, name string) (int64, error) {
	cleaned := clip(name, MaxName)
	if cleaned == "" {
		return 0, &Problem{"Give the playlist a name."}
	}
	var id int64
	err := l.pool.QueryRow(ctx,
		"INSERT INTO playlists (account_id, name, created_at) VALUES ($1, $2, $3) RETURNING id",
		accountID, cleaned, l.clock()).Scan(&id)
	return id, err
}

func (l *Library) DeletePlaylist(ctx context.Context, accountID, playlistID int64) error {
	_, err := l.pool.Exec(ctx, "DELETE FROM playlists WHERE account_id = $1 AND id = $2", accountID, playlistID)
	return err
}

func (l *Library) Playlist(ctx context.Context, accountID, playlistID int64) (Playlist, error) {
	p := Playlist{ID: playlistID}
	err := l.pool.QueryRow(ctx,
		"SELECT name FROM playlists WHERE account_id = $1 AND id = $2", accountID, playlistID,
	).Scan(&p.Name)
	if errors.Is(err, pgx.ErrNoRows) {
		return p, ErrNotFound
	}
	if err != nil {
		return p, err
	}
	rows, err := l.pool.Query(ctx, `
		SELECT video_id, title, channel, duration FROM playlist_items
		WHERE playlist_id = $1 ORDER BY position`, playlistID)
	if err != nil {
		return p, err
	}
	p.Items, err = pgx.CollectRows(rows, pgx.RowToStructByPos[Item])
	return p, err
}

// AddToPlaylist appends videos in order. A video already in the list keeps its place.
func (l *Library) AddToPlaylist(ctx context.Context, accountID, playlistID int64, items []Item) error {
	return pgx.BeginFunc(ctx, l.pool, func(tx pgx.Tx) error {
		var last int
		err := tx.QueryRow(ctx, `
			SELECT COALESCE((SELECT MAX(position) FROM playlist_items WHERE playlist_id = p.id), 0)
			FROM playlists p WHERE p.account_id = $1 AND p.id = $2 FOR UPDATE`,
			accountID, playlistID).Scan(&last)
		if errors.Is(err, pgx.ErrNoRows) {
			return &Problem{"That playlist no longer exists."}
		}
		if err != nil {
			return err
		}
		for _, item := range items {
			last++
			if _, err := tx.Exec(ctx, `
				INSERT INTO playlist_items (playlist_id, video_id, title, channel, duration, position)
				VALUES ($1, $2, $3, $4, $5, $6) ON CONFLICT DO NOTHING`,
				playlistID, item.ID, clip(item.Title, MaxTitle), clip(item.Channel, MaxTitle), item.Duration, last,
			); err != nil {
				return err
			}
		}
		return nil
	})
}

func (l *Library) RemoveFromPlaylist(ctx context.Context, accountID, playlistID int64, videoID string) error {
	_, err := l.pool.Exec(ctx, `
		DELETE FROM playlist_items i USING playlists p
		WHERE i.playlist_id = p.id AND p.account_id = $1 AND p.id = $2 AND i.video_id = $3`,
		accountID, playlistID, videoID)
	return err
}
