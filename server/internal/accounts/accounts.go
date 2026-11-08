// Package accounts finds the account a device token belongs to (ADR 0004).
//
// For now there's one account, joined by tokens set in config. Pairing and account creation
// come later and only change how devices get here: everything else already goes by account.
package accounts

import (
	"context"
	"crypto/sha256"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

var ErrUnknownToken = errors.New("unknown device token")

func hash(token string) []byte {
	sum := sha256.Sum256([]byte(token))
	return sum[:]
}

type Accounts struct{ pool *pgxpool.Pool }

func New(pool *pgxpool.Pool) *Accounts { return &Accounts{pool} }

// Authenticate returns the account a device token belongs to.
func (a *Accounts) Authenticate(ctx context.Context, token string) (int64, error) {
	if token == "" {
		return 0, ErrUnknownToken
	}
	var id int64
	err := a.pool.QueryRow(ctx, "SELECT account_id FROM devices WHERE token_hash = $1", hash(token)).Scan(&id)
	if errors.Is(err, pgx.ErrNoRows) {
		return 0, ErrUnknownToken
	}
	return id, err
}

// EnsureOwner makes sure the server's one account exists and each configured token is a
// device in it, removes any other device, and returns the account. Running it again
// changes nothing.
func (a *Accounts) EnsureOwner(ctx context.Context, tokens map[string]string) (int64, error) {
	var id int64
	err := pgx.BeginFunc(ctx, a.pool, func(tx pgx.Tx) error {
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
		hashes := make([][]byte, 0, len(tokens))
		for name, token := range tokens {
			hashes = append(hashes, hash(token))
			if _, err := tx.Exec(ctx, `
				INSERT INTO devices (account_id, token_hash, name) VALUES ($1, $2, $3)
				ON CONFLICT (token_hash) DO NOTHING`, id, hash(token), name); err != nil {
				return err
			}
		}
		// Config is the device list for now, so changing the token revokes the old one.
		_, err = tx.Exec(ctx,
			"DELETE FROM devices WHERE account_id = $1 AND NOT (token_hash = ANY($2))", id, hashes)
		return err
	})
	return id, err
}
