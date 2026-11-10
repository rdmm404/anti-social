package accounts_test

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/rdmm404/anti-social/server/internal/accounts"
	"github.com/rdmm404/anti-social/server/internal/accounts/memory"
	accountspg "github.com/rdmm404/anti-social/server/internal/accounts/postgres"
	"github.com/rdmm404/anti-social/server/internal/testdb"
)

func forEach(t *testing.T, test func(t *testing.T, svc *accounts.Service)) {
	t.Parallel()
	t.Run("memory", func(t *testing.T) {
		t.Parallel()
		test(t, accounts.NewService(memory.New(), nil))
	})
	t.Run("postgres", func(t *testing.T) {
		t.Parallel()
		test(t, accounts.NewService(accountspg.New(testdb.Optional(t)), nil))
	})
}

func TestChangingTheTokenSignsOutTheOldOne(t *testing.T) {
	forEach(t, func(t *testing.T, svc *accounts.Service) {
		ctx := context.Background()
		first, err := svc.EnsureOwner(ctx, map[string]string{"tv": "old-token"})
		if err != nil {
			t.Fatal(err)
		}
		if id, err := svc.Authenticate(ctx, "old-token"); err != nil || id != first {
			t.Fatalf("got %d, %v; want account %d", id, err, first)
		}
		again, err := svc.EnsureOwner(ctx, map[string]string{"tv": "new-token"})
		if err != nil || again != first {
			t.Fatalf("got %d, %v; want the same account %d", again, err, first)
		}
		for _, token := range []string{"old-token", "", "nonsense"} {
			if _, err := svc.Authenticate(ctx, token); err != accounts.ErrUnknownToken {
				t.Fatalf("%q: got %v, want ErrUnknownToken", token, err)
			}
		}
		if id, err := svc.Authenticate(ctx, "new-token"); err != nil || id != first {
			t.Fatalf("got %d, %v; want account %d", id, err, first)
		}
	})
}

func TestAPairedDeviceGetsItsOwnTokenOnce(t *testing.T) {
	forEach(t, func(t *testing.T, svc *accounts.Service) {
		ctx := context.Background()
		owner, err := svc.EnsureOwner(ctx, map[string]string{"configured": "owner-token"})
		if err != nil {
			t.Fatal(err)
		}
		p, err := svc.StartPairing(ctx, "  Fire TV  ")
		if err != nil {
			t.Fatal(err)
		}
		if token, err := svc.FinishPairing(ctx, p.Secret); err != nil || token != "" {
			t.Fatalf("before approval: %q, %v", token, err)
		}
		if _, err := svc.ApprovePairing(ctx, owner, "WRONGCOD"); err != accounts.ErrNoSuchPairing {
			t.Fatalf("wrong code: %v", err)
		}
		name, err := svc.ApprovePairing(ctx, owner, strings.ToLower(accounts.FormatCode(p.Code)))
		if err != nil || name != "Fire TV" {
			t.Fatalf("approve: %q, %v", name, err)
		}
		// One approval per code.
		if _, err := svc.ApprovePairing(ctx, owner, p.Code); err != accounts.ErrNoSuchPairing {
			t.Fatalf("approved twice: %v", err)
		}
		token, err := svc.FinishPairing(ctx, p.Secret)
		if err != nil || len(token) != 64 {
			t.Fatalf("finish: %q, %v", token, err)
		}
		if id, err := svc.Authenticate(ctx, token); err != nil || id != owner {
			t.Fatalf("paired token: %d, %v", id, err)
		}
		if _, err := svc.FinishPairing(ctx, p.Secret); err != accounts.ErrPairingGone {
			t.Fatalf("claimed twice: %v", err)
		}

		// Restarting with the same config keeps paired devices; only configured ones follow it.
		if _, err := svc.EnsureOwner(ctx, map[string]string{"configured": "new-owner-token"}); err != nil {
			t.Fatal(err)
		}
		if _, err := svc.Authenticate(ctx, token); err != nil {
			t.Fatalf("paired device after a config change: %v", err)
		}
		devices, err := svc.Devices(ctx, owner)
		if err != nil || len(devices) != 2 {
			t.Fatalf("devices: %+v, %v", devices, err)
		}
		configured, paired := devices[0], devices[1]
		if paired.Configured {
			configured, paired = paired, configured
		}
		if !configured.Configured || paired.Configured || paired.Name != "Fire TV" {
			t.Fatalf("devices: %+v", devices)
		}

		// The configured device can only change in config; the paired one can go.
		if err := svc.RemoveDevice(ctx, owner, configured.ID); err != accounts.ErrNoSuchDevice {
			t.Fatalf("removed the configured device: %v", err)
		}
		if err := svc.RemoveDevice(ctx, owner, paired.ID); err != nil {
			t.Fatal(err)
		}
		if _, err := svc.Authenticate(ctx, token); err != accounts.ErrUnknownToken {
			t.Fatalf("removed device: %v", err)
		}
	})
}

func TestPairingCodesExpire(t *testing.T) {
	now := time.Date(2026, 11, 9, 12, 0, 0, 0, time.UTC)
	clock := func() time.Time { return now }
	for name, store := range map[string]func(t *testing.T) accounts.Store{
		"memory":   func(*testing.T) accounts.Store { return memory.New() },
		"postgres": func(t *testing.T) accounts.Store { return accountspg.New(testdb.Optional(t)) },
	} {
		t.Run(name, func(t *testing.T) {
			ctx := context.Background()
			svc := accounts.NewService(store(t), clock)
			owner, err := svc.EnsureOwner(ctx, map[string]string{"configured": "owner-token"})
			if err != nil {
				t.Fatal(err)
			}
			late, _ := svc.StartPairing(ctx, "late")
			approved, _ := svc.StartPairing(ctx, "approved")
			if _, err := svc.ApprovePairing(ctx, owner, approved.Code); err != nil {
				t.Fatal(err)
			}
			now = now.Add(accounts.PairingLifetime)
			if _, err := svc.ApprovePairing(ctx, owner, late.Code); err != accounts.ErrNoSuchPairing {
				t.Fatalf("approved after expiry: %v", err)
			}
			if _, err := svc.FinishPairing(ctx, approved.Secret); err != accounts.ErrPairingGone {
				t.Fatalf("claimed after expiry: %v", err)
			}
		})
	}
}

func TestSigningOutRevokesOnlyThatDevice(t *testing.T) {
	forEach(t, func(t *testing.T, svc *accounts.Service) {
		ctx := context.Background()
		owner, _ := svc.EnsureOwner(ctx, map[string]string{"configured": "owner-token"})
		browser, err := svc.AddDevice(ctx, owner, "Browser")
		if err != nil {
			t.Fatal(err)
		}
		if err := svc.SignOut(ctx, browser); err != nil {
			t.Fatal(err)
		}
		if _, err := svc.Authenticate(ctx, browser); err != accounts.ErrUnknownToken {
			t.Fatalf("signed-out browser: %v", err)
		}
		// Signing out with the configured token can't remove it.
		svc.SignOut(ctx, "owner-token")
		if _, err := svc.Authenticate(ctx, "owner-token"); err != nil {
			t.Fatalf("configured token: %v", err)
		}
	})
}
