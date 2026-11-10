// Package postgres keeps accounts, devices and pairings in Postgres.
package postgres

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
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
				INSERT INTO devices (account_id, token_hash, name, configured) VALUES ($1, $2, $3, true)
				ON CONFLICT (token_hash) DO NOTHING`, id, d.TokenHash, d.Name); err != nil {
				return err
			}
		}
		_, err = tx.Exec(ctx, `
			DELETE FROM devices WHERE account_id = $1 AND configured AND NOT (token_hash = ANY($2))`, id, hashes)
		return err
	})
	return id, err
}

func (s *Store) AddDevice(ctx context.Context, accountID int64, d accounts.Device) error {
	_, err := s.pool.Exec(ctx,
		"INSERT INTO devices (account_id, token_hash, name) VALUES ($1, $2, $3)", accountID, d.TokenHash, d.Name)
	return err
}

func (s *Store) Devices(ctx context.Context, accountID int64) ([]accounts.DeviceInfo, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT id, name, configured, created_at FROM devices WHERE account_id = $1 ORDER BY created_at, id`, accountID)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (accounts.DeviceInfo, error) {
		var d accounts.DeviceInfo
		err := row.Scan(&d.ID, &d.Name, &d.Configured, &d.CreatedAt)
		return d, err
	})
}

func (s *Store) RemoveDevice(ctx context.Context, accountID, id int64) (bool, error) {
	tag, err := s.pool.Exec(ctx,
		"DELETE FROM devices WHERE account_id = $1 AND id = $2 AND NOT configured", accountID, id)
	return tag.RowsAffected() > 0, err
}

func (s *Store) RemoveToken(ctx context.Context, tokenHash []byte) error {
	_, err := s.pool.Exec(ctx, "DELETE FROM devices WHERE token_hash = $1 AND NOT configured", tokenHash)
	return err
}

func (s *Store) AddPairing(ctx context.Context, p accounts.Pairing, now time.Time, max int) error {
	return pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		// Serialises starts, so the cap holds under concurrent requests.
		if _, err := tx.Exec(ctx, "LOCK TABLE pairings IN EXCLUSIVE MODE"); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, "DELETE FROM pairings WHERE expires_at <= $1", now); err != nil {
			return err
		}
		var waiting int
		if err := tx.QueryRow(ctx, "SELECT count(*) FROM pairings").Scan(&waiting); err != nil {
			return err
		}
		if waiting >= max {
			return accounts.ErrTooManyPairings
		}
		_, err := tx.Exec(ctx, `
			INSERT INTO pairings (code, secret_hash, name, expires_at) VALUES ($1, $2, $3, $4)`,
			p.Code, p.SecretHash, p.Name, p.ExpiresAt)
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" && pgErr.ConstraintName == "pairings_code_key" {
			return accounts.ErrCodeTaken
		}
		return err
	})
}

func (s *Store) ApprovePairing(ctx context.Context, code string, accountID int64, now time.Time) (string, bool, error) {
	var name string
	err := s.pool.QueryRow(ctx, `
		UPDATE pairings SET account_id = $2
		WHERE code = $1 AND account_id IS NULL AND expires_at > $3
		RETURNING name`, code, accountID, now).Scan(&name)
	if errors.Is(err, pgx.ErrNoRows) {
		return "", false, nil
	}
	return name, err == nil, err
}

func (s *Store) ClaimPairing(ctx context.Context, secretHash []byte, now time.Time, tokenHash []byte) (accounts.ClaimState, error) {
	state := accounts.Gone
	err := pgx.BeginFunc(ctx, s.pool, func(tx pgx.Tx) error {
		var account *int64
		var id int64
		var name string
		err := tx.QueryRow(ctx, `
			SELECT id, account_id, name FROM pairings WHERE secret_hash = $1 AND expires_at > $2
			FOR UPDATE`, secretHash, now).Scan(&id, &account, &name)
		if errors.Is(err, pgx.ErrNoRows) {
			return nil
		}
		if err != nil {
			return err
		}
		if account == nil {
			state = accounts.Waiting
			return nil
		}
		if _, err := tx.Exec(ctx, "DELETE FROM pairings WHERE id = $1", id); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx,
			"INSERT INTO devices (account_id, token_hash, name) VALUES ($1, $2, $3)", *account, tokenHash, name); err != nil {
			return err
		}
		state = accounts.Claimed
		return nil
	})
	return state, err
}
