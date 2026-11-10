// The anti-social server: budget, sessions, settings and the account's library, for clients
// that get their video from YouTube themselves.
//
// This is the only place that knows every package: it builds the Postgres adapters, the
// services on them, and the HTTP routes over those.
package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/rdmm404/anti-social/server/db"
	"github.com/rdmm404/anti-social/server/internal/accounts"
	accountsapi "github.com/rdmm404/anti-social/server/internal/accounts/api"
	accountspg "github.com/rdmm404/anti-social/server/internal/accounts/postgres"
	"github.com/rdmm404/anti-social/server/internal/budget"
	budgetapi "github.com/rdmm404/anti-social/server/internal/budget/api"
	budgetpg "github.com/rdmm404/anti-social/server/internal/budget/postgres"
	"github.com/rdmm404/anti-social/server/internal/config"
	"github.com/rdmm404/anti-social/server/internal/httpapi"
	"github.com/rdmm404/anti-social/server/internal/library"
	libraryapi "github.com/rdmm404/anti-social/server/internal/library/api"
	librarypg "github.com/rdmm404/anti-social/server/internal/library/postgres"
	"github.com/rdmm404/anti-social/server/internal/web"
)

func main() {
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stderr, nil)))
	if err := run(); err != nil {
		slog.Error("server stopped", "err", err)
		os.Exit(1)
	}
}

type services struct {
	accounts *accounts.Service
	budget   *budget.Service
	library  *library.Service
}

func budgetConfig(cfg config.Config) budget.Config {
	return budget.Config{
		Location:      cfg.Location,
		DayStartHour:  cfg.DayStartHour,
		CoolingOff:    cfg.CoolingOff,
		CheckinExpiry: 60 * time.Second,
		CheckinEvery:  15 * time.Second,
		Seeds: map[string]int{
			budget.DailyMinutes:      cfg.DailyMinutes,
			budget.SessionGapMinutes: cfg.SessionGapMinutes,
		},
	}
}

// handler is every route: the JSON API, the settings page and the health check.
func handler(s services) http.Handler {
	mux := http.NewServeMux()
	auth := func(next http.HandlerFunc) http.HandlerFunc { return accountsapi.Authenticated(s.accounts, next) }

	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { io.WriteString(w, "ok\n") })
	budgetapi.New(s.budget).Register(mux, auth)
	libraryapi.New(s.library).Register(mux, auth)
	mux.HandleFunc("/api/", func(w http.ResponseWriter, _ *http.Request) { httpapi.NotFound(w) })
	web.New(s.accounts, s.budget, s.library).Register(mux)
	return httpapi.Logged(mux)
}

func run() error {
	cfg, err := config.Load()
	if err != nil {
		return err
	}
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	pool, err := db.Open(ctx, cfg.DatabaseURL)
	if err != nil {
		return err
	}
	defer pool.Close()

	s := services{
		accounts: accounts.NewService(accountspg.New(pool)),
		budget:   budget.NewService(budgetpg.New(pool), budgetConfig(cfg), nil),
		library:  library.NewService(librarypg.New(pool), nil),
	}
	if _, err := s.accounts.EnsureOwner(ctx, map[string]string{"configured": cfg.Token}); err != nil {
		return err
	}

	server := &http.Server{
		Addr:              cfg.Addr,
		Handler:           handler(s),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		server.Shutdown(shutdown)
	}()
	slog.Info("listening", "addr", cfg.Addr, "timezone", cfg.Location.String())
	if err := server.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}
