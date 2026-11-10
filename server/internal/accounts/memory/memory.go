// Package memory keeps accounts in memory, for tests.
package memory

import (
	"context"
	"sync"

	"github.com/rdmm404/anti-social/server/internal/accounts"
)

type Store struct {
	mu       sync.Mutex
	accounts []int64
	devices  map[string]int64 // token hash → account
}

var _ accounts.Store = (*Store)(nil)

func New() *Store { return &Store{devices: map[string]int64{}} }

func (s *Store) AccountFor(_ context.Context, tokenHash []byte) (int64, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	id, ok := s.devices[string(tokenHash)]
	return id, ok, nil
}

func (s *Store) SyncOwner(_ context.Context, devices []accounts.Device) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.accounts) == 0 {
		s.accounts = append(s.accounts, 1)
	}
	owner := s.accounts[0]
	for hash, id := range s.devices {
		if id == owner {
			delete(s.devices, hash)
		}
	}
	for _, d := range devices {
		s.devices[string(d.TokenHash)] = owner
	}
	return owner, nil
}

// AddAccount makes another account with one device, for tests about keeping accounts apart.
func (s *Store) AddAccount(token string) int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	id := int64(len(s.accounts) + 1)
	s.accounts = append(s.accounts, id)
	s.devices[string(accounts.Hash(token))] = id
	return id
}
