// Package postgres keeps the library in Postgres.
package postgres

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/rdmm404/anti-social/server/internal/library"
)

type Store struct{ pool *pgxpool.Pool }

var _ library.Store = (*Store)(nil)

func New(pool *pgxpool.Pool) *Store { return &Store{pool} }

func (s *Store) Subscriptions(ctx context.Context, accountID int64) ([]library.Subscription, error) {
	rows, err := s.pool.Query(ctx,
		"SELECT channel_id, name FROM subscriptions WHERE account_id = $1 ORDER BY lower(name), channel_id", accountID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByPos[library.Subscription])
}

func (s *Store) SaveSubscription(ctx context.Context, accountID int64, sub library.Subscription, at time.Time) error {
	_, err := s.pool.Exec(ctx, `
		INSERT INTO subscriptions (account_id, channel_id, name, added_at) VALUES ($1, $2, $3, $4)
		ON CONFLICT (account_id, channel_id) DO UPDATE SET name = excluded.name`,
		accountID, sub.ChannelID, sub.Name, at)
	return err
}

func (s *Store) DeleteSubscription(ctx context.Context, accountID int64, channelID string) error {
	_, err := s.pool.Exec(ctx,
		"DELETE FROM subscriptions WHERE account_id = $1 AND channel_id = $2", accountID, channelID)
	return err
}

func (s *Store) Playlists(ctx context.Context, accountID int64) ([]library.PlaylistSummary, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT p.id, p.name, COUNT(i.video_id)::int FROM playlists p
		LEFT JOIN playlist_items i ON i.playlist_id = p.id
		WHERE p.account_id = $1 GROUP BY p.id ORDER BY lower(p.name), p.id`, accountID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByPos[library.PlaylistSummary])
}

func (s *Store) CreatePlaylist(ctx context.Context, accountID int64, name string, at time.Time) (int64, error) {
	var id int64
	err := s.pool.QueryRow(ctx,
		"INSERT INTO playlists (account_id, name, created_at) VALUES ($1, $2, $3) RETURNING id",
		accountID, name, at).Scan(&id)
	return id, err
}

func (s *Store) DeletePlaylist(ctx context.Context, accountID, playlistID int64) error {
	_, err := s.pool.Exec(ctx, "DELETE FROM playlists WHERE account_id = $1 AND id = $2", accountID, playlistID)
	return err
}

func (s *Store) Playlist(ctx context.Context, accountID, playlistID int64) (*library.Playlist, error) {
	p := library.Playlist{ID: playlistID}
	err := s.pool.QueryRow(ctx,
		"SELECT name FROM playlists WHERE account_id = $1 AND id = $2", accountID, playlistID,
	).Scan(&p.Name)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	rows, err := s.pool.Query(ctx, `
		SELECT video_id, title, channel, duration FROM playlist_items
		WHERE playlist_id = $1 ORDER BY position`, playlistID)
	if err != nil {
		return nil, err
	}
	p.Items, err = pgx.CollectRows(rows, pgx.RowToStructByPos[library.Item])
	return &p, err
}

func (s *Store) AppendItems(ctx context.Context, accountID, playlistID int64, items []library.Item) (bool, error) {
	found := false
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		var last int
		err := tx.QueryRow(ctx, `
			SELECT COALESCE((SELECT MAX(position) FROM playlist_items WHERE playlist_id = p.id), 0)
			FROM playlists p WHERE p.account_id = $1 AND p.id = $2 FOR UPDATE`,
			accountID, playlistID).Scan(&last)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		found = true
		for _, item := range items {
			last++
			if _, err := tx.Exec(ctx, `
				INSERT INTO playlist_items (playlist_id, video_id, title, channel, duration, position)
				VALUES ($1, $2, $3, $4, $5, $6) ON CONFLICT DO NOTHING`,
				playlistID, item.VideoID, item.Title, item.Channel, item.Duration, last,
			); err != nil {
				return err
			}
		}
		return nil
	})
	return found, err
}

func (s *Store) RemoveItem(ctx context.Context, accountID, playlistID int64, videoID string) error {
	_, err := s.pool.Exec(ctx, `
		DELETE FROM playlist_items i USING playlists p
		WHERE i.playlist_id = p.id AND p.account_id = $1 AND p.id = $2 AND i.video_id = $3`,
		accountID, playlistID, videoID)
	return err
}
