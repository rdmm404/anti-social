package ledger

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"
)

const (
	DailyMinutes      = "daily_minutes"
	SessionGapMinutes = "session_gap_minutes"
)

type limits struct {
	low, high      int
	higherIsLooser bool
}

// Settings an account can change, and which direction loosens each.
var settingLimits = map[string]limits{
	DailyMinutes:      {0, 1440, true},
	SessionGapMinutes: {0, 1440, false},
}

// SettingNames lists the settings in the order pages show them.
var SettingNames = []string{DailyMinutes, SessionGapMinutes}

// Bounds returns a setting's lowest and highest values.
func Bounds(key string) (int, int) { return settingLimits[key].low, settingLimits[key].high }

func (a *account) applyDueChanges() error {
	_, err := a.tx.Exec(a.ctx, `
		WITH due AS (
			DELETE FROM pending_changes WHERE account_id = $1 AND apply_at <= $2
			RETURNING key, value, apply_at
		), latest AS (
			SELECT DISTINCT ON (key) key, value FROM due ORDER BY key, apply_at DESC
		)
		INSERT INTO settings (account_id, key, value) SELECT $1, key, value FROM latest
		ON CONFLICT (account_id, key) DO UPDATE SET value = excluded.value`,
		a.id, a.now,
	)
	return err
}

// settings are the account's current values, falling back to the seeds for any never changed.
func (a *account) settings() (map[string]int, error) {
	values := make(map[string]int, len(settingLimits))
	for key := range settingLimits {
		values[key] = a.cfg.Seeds[key]
	}
	rows, err := a.tx.Query(a.ctx, "SELECT key, value FROM settings WHERE account_id = $1", a.id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var key string
		var value int
		if err := rows.Scan(&key, &value); err != nil {
			return nil, err
		}
		if _, known := settingLimits[key]; known {
			values[key] = value
		}
	}
	return values, rows.Err()
}

type PendingChange struct {
	ID      int64     `json:"id"`
	Key     string    `json:"key"`
	Value   int       `json:"value"`
	ApplyAt time.Time `json:"-"`
	// Unix seconds, for clients.
	ApplyAtUnix float64 `json:"apply_at"`
}

func (a *account) pendingChanges() ([]PendingChange, error) {
	rows, err := a.tx.Query(a.ctx,
		"SELECT id, key, value, apply_at FROM pending_changes WHERE account_id = $1 ORDER BY apply_at, id", a.id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	changes := []PendingChange{}
	for rows.Next() {
		var c PendingChange
		if err := rows.Scan(&c.ID, &c.Key, &c.Value, &c.ApplyAt); err != nil {
			return nil, err
		}
		c.ApplyAtUnix = unix(c.ApplyAt)
		changes = append(changes, c)
	}
	return changes, rows.Err()
}

type SettingsView struct {
	Settings        map[string]int  `json:"settings"`
	Pending         []PendingChange `json:"pending"`
	CoolingOffHours int             `json:"cooling_off_hours"`
}

func (l *Ledger) Settings(ctx context.Context, accountID int64) (SettingsView, error) {
	var view SettingsView
	err := l.within(ctx, accountID, func(a *account) (err error) {
		if view.Settings, err = a.settings(); err != nil {
			return err
		}
		view.Pending, err = a.pendingChanges()
		view.CoolingOffHours = int(a.cfg.CoolingOff.Hours())
		return err
	})
	return view, err
}

type ChangeResult struct {
	Applied []string `json:"applied"`
	Queued  []string `json:"queued"`
}

// ChangeSettings applies tightening now; loosening waits out the cooling-off period.
//
// Any new value for a setting replaces a queued one, so re-requesting a loosening restarts
// its clock instead of stacking up. Saving the same queued value again leaves the clock alone.
func (l *Ledger) ChangeSettings(ctx context.Context, accountID int64, changes map[string]int) (ChangeResult, error) {
	result := ChangeResult{Applied: []string{}, Queued: []string{}}
	for key, value := range changes {
		lim, ok := settingLimits[key]
		if !ok {
			return result, refuse("Unknown setting: %s", key)
		}
		if value < lim.low || value > lim.high {
			return result, refuse("%s must be between %d and %d", key, lim.low, lim.high)
		}
	}
	err := l.within(ctx, accountID, func(a *account) error {
		current, err := a.settings()
		if err != nil {
			return err
		}
		// In a fixed order, so results don't depend on map iteration.
		for _, key := range SettingNames {
			value, asked := changes[key]
			if !asked {
				continue
			}
			var pending *int
			if err := a.tx.QueryRow(a.ctx,
				"SELECT value FROM pending_changes WHERE account_id = $1 AND key = $2", a.id, key,
			).Scan(&pending); err != nil && !errors.Is(err, pgx.ErrNoRows) {
				return err
			}
			if pending != nil && *pending == value {
				continue
			}
			if _, err := a.tx.Exec(a.ctx,
				"DELETE FROM pending_changes WHERE account_id = $1 AND key = $2", a.id, key); err != nil {
				return err
			}
			if value == current[key] {
				continue
			}
			loosens := value < current[key]
			if settingLimits[key].higherIsLooser {
				loosens = value > current[key]
			}
			if loosens {
				_, err = a.tx.Exec(a.ctx, `
					INSERT INTO pending_changes (account_id, key, value, requested_at, apply_at)
					VALUES ($1, $2, $3, $4, $5)`,
					a.id, key, value, a.now, a.now.Add(a.cfg.CoolingOff))
				result.Queued = append(result.Queued, key)
			} else {
				_, err = a.tx.Exec(a.ctx, `
					INSERT INTO settings (account_id, key, value) VALUES ($1, $2, $3)
					ON CONFLICT (account_id, key) DO UPDATE SET value = excluded.value`,
					a.id, key, value)
				result.Applied = append(result.Applied, key)
			}
			if err != nil {
				return err
			}
		}
		return nil
	})
	return result, err
}

func (l *Ledger) CancelChange(ctx context.Context, accountID, changeID int64) error {
	return l.within(ctx, accountID, func(a *account) error {
		_, err := a.tx.Exec(a.ctx,
			"DELETE FROM pending_changes WHERE account_id = $1 AND id = $2", a.id, changeID)
		return err
	})
}
