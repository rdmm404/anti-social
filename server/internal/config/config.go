// Package config reads the server's settings from the environment.
//
// Limits live in the database behind cooling-off. Values here are the seeds an account's
// settings start from, and things that deliberately can't be changed from a client.
package config

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"time"
)

type Config struct {
	Addr        string
	DatabaseURL string
	// The first device's token. It signs in the settings page, which approves the rest by
	// pairing (ADR 0004).
	Token        string
	Location     *time.Location
	DayStartHour int
	CoolingOff   time.Duration
	// First values for a new account's settings.
	DailyMinutes      int
	SessionGapMinutes int
}

func Load() (Config, error) {
	var errs []error
	integer := func(name string, fallback int) int {
		raw, ok := os.LookupEnv(name)
		if !ok || raw == "" {
			return fallback
		}
		v, err := strconv.Atoi(raw)
		if err != nil {
			errs = append(errs, fmt.Errorf("%s must be a whole number", name))
		}
		return v
	}
	c := Config{
		Addr:              env("ANTI_SOCIAL_ADDR", ":8080"),
		DatabaseURL:       os.Getenv("ANTI_SOCIAL_DATABASE_URL"),
		Token:             os.Getenv("ANTI_SOCIAL_TOKEN"),
		DayStartHour:      integer("ANTI_SOCIAL_DAY_START", 4),
		CoolingOff:        time.Duration(integer("ANTI_SOCIAL_COOLING_OFF_HOURS", 72)) * time.Hour,
		DailyMinutes:      integer("ANTI_SOCIAL_DAILY_MINUTES", 120),
		SessionGapMinutes: integer("ANTI_SOCIAL_SESSION_GAP_MINUTES", 20),
	}
	// TZ is what the container sets; time.Local already follows it.
	c.Location = time.Local
	if c.DatabaseURL == "" {
		errs = append(errs, errors.New("ANTI_SOCIAL_DATABASE_URL is required"))
	}
	if len(c.Token) < 32 {
		errs = append(errs, errors.New("ANTI_SOCIAL_TOKEN must be at least 32 characters; make one with `openssl rand -hex 32`"))
	}
	if c.DayStartHour < 0 || c.DayStartHour > 23 {
		errs = append(errs, errors.New("ANTI_SOCIAL_DAY_START must be an hour from 0 to 23"))
	}
	return c, errors.Join(errs...)
}

func env(name, fallback string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return fallback
}
