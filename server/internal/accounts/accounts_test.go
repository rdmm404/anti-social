package accounts_test

import (
	"context"
	"testing"

	"github.com/rdmm404/anti-social/server/internal/accounts"
	"github.com/rdmm404/anti-social/server/internal/accounts/memory"
	accountspg "github.com/rdmm404/anti-social/server/internal/accounts/postgres"
	"github.com/rdmm404/anti-social/server/internal/testdb"
)

func forEach(t *testing.T, test func(t *testing.T, svc *accounts.Service)) {
	t.Parallel()
	t.Run("memory", func(t *testing.T) {
		t.Parallel()
		test(t, accounts.NewService(memory.New()))
	})
	t.Run("postgres", func(t *testing.T) {
		t.Parallel()
		test(t, accounts.NewService(accountspg.New(testdb.Optional(t))))
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
