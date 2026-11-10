package budget

import (
	"context"
	"slices"
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

// applyDueChanges applies the changes whose cooling-off has ended. When several for one
// setting are due, the one applying last wins.
func (o *op) applyDueChanges() error {
	pending, err := o.r.PendingChanges()
	if err != nil {
		return err
	}
	due := map[string]int{}
	for _, c := range pending {
		if c.ApplyAt.After(o.now) {
			continue
		}
		due[c.Key] = c.Value
		if err := o.r.DeletePendingChange(c.ID); err != nil {
			return err
		}
	}
	for key, value := range due {
		if err := o.r.SaveSetting(key, value); err != nil {
			return err
		}
	}
	return nil
}

// settings are the account's current values, falling back to the seeds for any never changed.
func (o *op) settings() (map[string]int, error) {
	values := make(map[string]int, len(settingLimits))
	for key := range settingLimits {
		values[key] = o.cfg.Seeds[key]
	}
	saved, err := o.r.SavedSettings()
	if err != nil {
		return nil, err
	}
	for key, value := range saved {
		if _, known := settingLimits[key]; known {
			values[key] = value
		}
	}
	return values, nil
}

func (s *Service) Settings(ctx context.Context, accountID int64) (SettingsView, error) {
	view := SettingsView{CoolingOff: s.cfg.CoolingOff}
	err := s.within(ctx, accountID, func(o *op) (err error) {
		if view.Values, err = o.settings(); err != nil {
			return err
		}
		view.Pending, err = o.r.PendingChanges()
		return err
	})
	return view, err
}

// ChangeSettings applies tightening now; loosening waits out the cooling-off period.
//
// Any new value for a setting replaces a queued one, so re-requesting a loosening restarts
// its clock instead of stacking up. Saving the same queued value again leaves the clock alone.
func (s *Service) ChangeSettings(ctx context.Context, accountID int64, changes map[string]int) (ChangeResult, error) {
	result := ChangeResult{Applied: []string{}, Queued: []string{}}
	for key, value := range changes {
		lim, ok := settingLimits[key]
		if !ok {
			return result, invalid("Unknown setting: %s", key)
		}
		if value < lim.low || value > lim.high {
			return result, invalid("%s must be between %d and %d", key, lim.low, lim.high)
		}
	}
	err := s.within(ctx, accountID, func(o *op) error {
		current, err := o.settings()
		if err != nil {
			return err
		}
		pending, err := o.r.PendingChanges()
		if err != nil {
			return err
		}
		// In a fixed order, so results don't depend on map iteration.
		for _, key := range SettingNames {
			value, asked := changes[key]
			if !asked {
				continue
			}
			queued := slices.DeleteFunc(slices.Clone(pending), func(c PendingChange) bool { return c.Key != key })
			if slices.ContainsFunc(queued, func(c PendingChange) bool { return c.Value == value }) {
				continue
			}
			for _, c := range queued {
				if err := o.r.DeletePendingChange(c.ID); err != nil {
					return err
				}
			}
			if value == current[key] {
				continue
			}
			loosens := value < current[key]
			if settingLimits[key].higherIsLooser {
				loosens = value > current[key]
			}
			if loosens {
				err = o.r.QueueChange(PendingChange{Key: key, Value: value, RequestedAt: o.now, ApplyAt: o.now.Add(o.cfg.CoolingOff)})
				result.Queued = append(result.Queued, key)
			} else {
				err = o.r.SaveSetting(key, value)
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

func (s *Service) CancelChange(ctx context.Context, accountID, changeID int64) error {
	return s.within(ctx, accountID, func(o *op) error {
		return o.r.DeletePendingChange(changeID)
	})
}
