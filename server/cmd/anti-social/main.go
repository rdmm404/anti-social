// The anti-social server: budget, sessions, settings and the account's library, for clients
// that get their video from YouTube themselves.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/rdmm404/anti-social/server/db"
	"github.com/rdmm404/anti-social/server/internal/accounts"
	"github.com/rdmm404/anti-social/server/internal/api"
	"github.com/rdmm404/anti-social/server/internal/config"
	"github.com/rdmm404/anti-social/server/internal/ledger"
	"github.com/rdmm404/anti-social/server/internal/library"
)

func main() {
	log := slog.New(slog.NewTextHandler(os.Stderr, nil))
	if err := run(log); err != nil {
		log.Error("server stopped", "err", err)
		os.Exit(1)
	}
}

func run(log *slog.Logger) error {
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

	accts := accounts.New(pool)
	if _, err := accts.EnsureOwner(ctx, map[string]string{"configured": cfg.Token}); err != nil {
		return err
	}
	l := ledger.New(pool, ledger.Config{
		Location:      cfg.Location,
		DayStartHour:  cfg.DayStartHour,
		CoolingOff:    cfg.CoolingOff,
		CheckinExpiry: 60 * time.Second,
		CheckinEvery:  15 * time.Second,
		Seeds: map[string]int{
			ledger.DailyMinutes:      cfg.DailyMinutes,
			ledger.SessionGapMinutes: cfg.SessionGapMinutes,
		},
	}, nil)

	server := &http.Server{
		Addr:              cfg.Addr,
		Handler:           api.New(accts, l, library.New(pool, nil), log).Handler(),
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
	log.Info("listening", "addr", cfg.Addr, "timezone", cfg.Location.String())
	if err := server.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}
