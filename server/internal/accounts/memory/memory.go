// Package memory keeps accounts in memory, for tests.
package memory

import (
	"context"
	"slices"
	"sync"
	"time"

	"github.com/rdmm404/anti-social/server/internal/accounts"
)

type device struct {
	accounts.DeviceInfo
	account int64
	hash    string
}

type pairing struct {
	accounts.Pairing
	account int64 // 0 until approved
}

type Store struct {
	mu       sync.Mutex
	accounts []int64
	devices  []device
	pairings []pairing
	lastID   int64
}

var _ accounts.Store = (*Store)(nil)

func New() *Store { return &Store{} }

func (s *Store) AccountFor(_ context.Context, tokenHash []byte) (int64, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, d := range s.devices {
		if d.hash == string(tokenHash) {
			return d.account, true, nil
		}
	}
	return 0, false, nil
}

func (s *Store) add(account int64, d accounts.Device, configured bool) {
	s.lastID++
	s.devices = append(s.devices, device{
		DeviceInfo: accounts.DeviceInfo{ID: s.lastID, Name: d.Name, Configured: configured, CreatedAt: time.Now()},
		account:    account,
		hash:       string(d.TokenHash),
	})
}

func (s *Store) SyncOwner(_ context.Context, devices []accounts.Device) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.accounts) == 0 {
		s.accounts = append(s.accounts, 1)
	}
	owner := s.accounts[0]
	wanted := map[string]bool{}
	for _, d := range devices {
		wanted[string(d.TokenHash)] = true
	}
	s.devices = slices.DeleteFunc(s.devices, func(d device) bool {
		return d.account == owner && d.Configured && !wanted[d.hash]
	})
	for _, d := range devices {
		if !slices.ContainsFunc(s.devices, func(e device) bool { return e.hash == string(d.TokenHash) }) {
			s.add(owner, d, true)
		}
	}
	return owner, nil
}

func (s *Store) AddDevice(_ context.Context, accountID int64, d accounts.Device) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.add(accountID, d, false)
	return nil
}

func (s *Store) Devices(_ context.Context, accountID int64) ([]accounts.DeviceInfo, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var out []accounts.DeviceInfo
	for _, d := range s.devices {
		if d.account == accountID {
			out = append(out, d.DeviceInfo)
		}
	}
	return out, nil
}

func (s *Store) RemoveDevice(_ context.Context, accountID, id int64) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	before := len(s.devices)
	s.devices = slices.DeleteFunc(s.devices, func(d device) bool {
		return d.account == accountID && d.ID == id && !d.Configured
	})
	return len(s.devices) < before, nil
}

func (s *Store) RemoveToken(_ context.Context, tokenHash []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.devices = slices.DeleteFunc(s.devices, func(d device) bool { return d.hash == string(tokenHash) && !d.Configured })
	return nil
}

func (s *Store) AddPairing(_ context.Context, p accounts.Pairing, now time.Time, max int) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.pairings = slices.DeleteFunc(s.pairings, func(q pairing) bool { return !q.ExpiresAt.After(now) })
	if slices.ContainsFunc(s.pairings, func(q pairing) bool { return q.Code == p.Code }) {
		return accounts.ErrCodeTaken
	}
	if len(s.pairings) >= max {
		return accounts.ErrTooManyPairings
	}
	s.pairings = append(s.pairings, pairing{Pairing: p})
	return nil
}

func (s *Store) ApprovePairing(_ context.Context, code string, accountID int64, now time.Time) (string, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, p := range s.pairings {
		if p.Code == code && p.account == 0 && p.ExpiresAt.After(now) {
			s.pairings[i].account = accountID
			return p.Name, true, nil
		}
	}
	return "", false, nil
}

func (s *Store) ClaimPairing(_ context.Context, secretHash []byte, now time.Time, tokenHash []byte) (accounts.ClaimState, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, p := range s.pairings {
		if string(p.SecretHash) != string(secretHash) || !p.ExpiresAt.After(now) {
			continue
		}
		if p.account == 0 {
			return accounts.Waiting, nil
		}
		s.pairings = slices.Delete(s.pairings, i, i+1)
		s.add(p.account, accounts.Device{Name: p.Name, TokenHash: tokenHash}, false)
		return accounts.Claimed, nil
	}
	return accounts.Gone, nil
}

// AddAccount makes another account with one device, for tests about keeping accounts apart.
func (s *Store) AddAccount(token string) int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	id := int64(len(s.accounts) + 1)
	s.accounts = append(s.accounts, id)
	s.add(id, accounts.Device{Name: "test", TokenHash: accounts.Hash(token)}, true)
	return id
}
