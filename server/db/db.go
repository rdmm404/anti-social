// Package db holds the schema, as dbmate migrations, and opens Postgres.
//
// Migrations live in migrations/ in dbmate's format and are embedded in the binary, which
// applies them on start. Make new ones with the dbmate CLI from server/:
//
//	dbmate new add_something
//
// Migrations only go forward once they've reached a shared database: fix a mistake with a
// new migration rather than editing an applied one.
package db

import (
	"context"
	"embed"
	"fmt"
	"io"
	"net/url"

	"github.com/amacneil/dbmate/v2/pkg/dbmate"
	_ "github.com/amacneil/dbmate/v2/pkg/driver/postgres"
	"github.com/jackc/pgx/v5/pgxpool"
)

//go:embed migrations/*.sql
var migrations embed.FS

// Open applies any migrations the database hasn't seen yet, then connects with pgx.
func Open(ctx context.Context, databaseURL string) (*pgxpool.Pool, error) {
	if err := Migrate(databaseURL); err != nil {
		return nil, err
	}
	pool, err := pgxpool.New(ctx, databaseURL)
	if err != nil {
		return nil, fmt.Errorf("connect: %w", err)
	}
	return pool, nil
}

// Migrate brings the schema up to date. The lock makes servers starting together wait
// for each other instead of racing.
func Migrate(databaseURL string) error {
	u, err := url.Parse(databaseURL)
	if err != nil {
		return fmt.Errorf("database url: %w", err)
	}
	m := dbmate.New(u)
	m.FS = migrations
	m.MigrationsDir = []string{"migrations"}
	m.AutoDumpSchema = false // The CLI writes db/schema.sql; the server has no pg_dump.
	m.UseMigrationLock = true
	m.Strict = true
	m.Log = io.Discard
	if err := m.Migrate(); err != nil {
		return fmt.Errorf("migrate: %w", err)
	}
	return nil
}
