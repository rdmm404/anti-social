// Package accounts finds the account a device token belongs to (ADR 0004).
//
// For now there's one account, joined by tokens set in config. Pairing and account creation
// come later and only change how devices get here: everything else already goes by account.
package accounts

import (
	"context"
	"crypto/sha256"

	"github.com/rdmm404/anti-social/server/internal/apperr"
)

// Device is a token that may act for the account. Only a hash of the token is kept, so a
// leaked database holds no usable tokens.
type Device struct {
	Name      string
	TokenHash []byte
}

type Store interface {
	// AccountFor returns the account a device belongs to, and false when no device has the hash.
	AccountFor(ctx context.Context, tokenHash []byte) (int64, bool, error)
	// SyncOwner makes sure the first account exists, holds exactly these devices, and returns
	// it. It runs as one change, so two servers starting at once agree on the account.
	SyncOwner(ctx context.Context, devices []Device) (int64, error)
}

var ErrUnknownToken = apperr.New(apperr.Unauthorized, "This device isn't signed in.")

func Hash(token string) []byte {
	sum := sha256.Sum256([]byte(token))
	return sum[:]
}

type Service struct{ store Store }

func NewService(store Store) *Service { return &Service{store} }

// Authenticate returns the account a device token belongs to, or ErrUnknownToken.
func (s *Service) Authenticate(ctx context.Context, token string) (int64, error) {
	if token == "" {
		return 0, ErrUnknownToken
	}
	id, ok, err := s.store.AccountFor(ctx, Hash(token))
	if err != nil {
		return 0, err
	}
	if !ok {
		return 0, ErrUnknownToken
	}
	return id, nil
}

// EnsureOwner makes sure the server's one account exists and holds exactly the configured
// tokens, keyed by device name. Config is the device list for now, so changing the token
// signs out the old one. Running it again changes nothing.
func (s *Service) EnsureOwner(ctx context.Context, tokens map[string]string) (int64, error) {
	devices := make([]Device, 0, len(tokens))
	for name, token := range tokens {
		devices = append(devices, Device{Name: name, TokenHash: Hash(token)})
	}
	return s.store.SyncOwner(ctx, devices)
}
