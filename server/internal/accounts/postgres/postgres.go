// Package postgres keeps accounts and devices in Postgres.
package postgres

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/rdmm404/anti-social/server/internal/accounts"
)

type Store struct{ pool *pgxpool.Pool }

var _ accounts.Store = (*Store)(nil)

func New(pool *pgxpool.Pool) *Store { return &Store{pool} }

func (s *Store) AccountFor(ctx context.Context, tokenHash []byte) (int64, bool, error) {
	var id int64
	err := s.pool.QueryRow(ctx, "SELECT account_id FROM devices WHERE token_hash = $1", tokenHash).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, false, nil
	}
	return id, err == nil, err
}

func (s *Store) SyncOwner(ctx context.Context, devices []accounts.Device) (int64, error) {
	var id int64
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, "LOCK TABLE accounts IN EXCLUSIVE MODE"); err != nil {
			return err
		}
		err := tx.QueryRow(ctx, "SELECT id FROM accounts ORDER BY id LIMIT 1").Scan(&id)
		if errors.Is(err, pgx.ErrNoRows) {
			err = tx.QueryRow(ctx, "INSERT INTO accounts DEFAULT VALUES RETURNING id").Scan(&id)
		}
		if err != nil {
			return err
		}
		hashes := make([][]byte, 0, len(devices))
		for _, d := range devices {
			hashes = append(hashes, d.TokenHash)
			if _, err := tx.Exec(ctx, `
				INSERT INTO devices (account_id, token_hash, name) VALUES ($1, $2, $3)
				ON CONFLICT (token_hash) DO NOTHING`, id, d.TokenHash, d.Name); err != nil {
				return err
			}
		}
		_, err = tx.Exec(ctx,
			"DELETE FROM devices WHERE account_id = $1 AND NOT (token_hash = ANY($2))", id, hashes)
		return err
	})
	return id, err
}
