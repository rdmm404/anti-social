// Package testdb gives each test its own freshly migrated Postgres database.
//
// Tests need ANTI_SOCIAL_TEST_DATABASE_URL pointing at a Postgres whose user may create
// databases, such as the one `mise run db` starts. Without it they fail rather than skip,
// so a missing database can't pass for a green run.
package testdb

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net/url"
	"os"
	"testing"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/rdmm404/anti-social/server/db"
)

const envVar = "ANTI_SOCIAL_TEST_DATABASE_URL"

// New creates an empty database, migrates it, and drops it when the test ends.
func New(t testing.TB) *pgxpool.Pool {
	t.Helper()
	admin := os.Getenv(envVar)
	if admin == "" {
		t.Fatalf("%s isn't set. Start a database with `mise run db` and export the URL it prints.", envVar)
	}
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, admin)
	if err != nil {
		t.Fatalf("connect to test postgres: %v", err)
	}
	defer conn.Close(ctx)

	suffix := make([]byte, 6)
	rand.Read(suffix)
	name := "test_" + hex.EncodeToString(suffix)
	if _, err := conn.Exec(ctx, "CREATE DATABASE "+name); err != nil {
		t.Fatalf("create database: %v", err)
	}

	u, err := url.Parse(admin)
	if err != nil {
		t.Fatal(err)
	}
	u.Path = "/" + name
	pool, err := db.Open(ctx, u.String())
	if err != nil {
		t.Fatalf("open test database: %v", err)
	}
	t.Cleanup(func() {
		pool.Close()
		conn, err := pgx.Connect(ctx, admin)
		if err != nil {
			return
		}
		defer conn.Close(ctx)
		conn.Exec(ctx, "DROP DATABASE IF EXISTS "+name+" WITH (FORCE)")
	})
	return pool
}
