// Package memory keeps the library in memory, for tests. It behaves as the Postgres store
// does, which the library's tests check by running against both.
package memory

import (
	"cmp"
	"context"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/rdmm404/anti-social/server/internal/library"
)

type playlist struct {
	account int64
	name    string
	items   []library.Item
}

type Store struct {
	mu            sync.Mutex
	subscriptions map[int64]map[string]library.Subscription
	playlists     map[int64]*playlist
	nextID        int64
}

var _ library.Store = (*Store)(nil)

func New() *Store {
	return &Store{subscriptions: map[int64]map[string]library.Subscription{}, playlists: map[int64]*playlist{}}
}

func (s *Store) Subscriptions(_ context.Context, accountID int64) ([]library.Subscription, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	subs := []library.Subscription{}
	for _, sub := range s.subscriptions[accountID] {
		subs = append(subs, sub)
	}
	slices.SortFunc(subs, func(a, b library.Subscription) int {
		return cmp.Or(cmp.Compare(strings.ToLower(a.Name), strings.ToLower(b.Name)), cmp.Compare(a.ChannelID, b.ChannelID))
	})
	return subs, nil
}

func (s *Store) SaveSubscription(_ context.Context, accountID int64, sub library.Subscription, _ time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.subscriptions[accountID] == nil {
		s.subscriptions[accountID] = map[string]library.Subscription{}
	}
	s.subscriptions[accountID][sub.ChannelID] = sub
	return nil
}

func (s *Store) DeleteSubscription(_ context.Context, accountID int64, channelID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	delete(s.subscriptions[accountID], channelID)
	return nil
}

func (s *Store) Playlists(_ context.Context, accountID int64) ([]library.PlaylistSummary, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	lists := []library.PlaylistSummary{}
	for id, p := range s.playlists {
		if p.account == accountID {
			lists = append(lists, library.PlaylistSummary{ID: id, Name: p.name, Count: len(p.items)})
		}
	}
	slices.SortFunc(lists, func(a, b library.PlaylistSummary) int {
		return cmp.Or(cmp.Compare(strings.ToLower(a.Name), strings.ToLower(b.Name)), cmp.Compare(a.ID, b.ID))
	})
	return lists, nil
}

func (s *Store) CreatePlaylist(_ context.Context, accountID int64, name string, _ time.Time) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.nextID++
	s.playlists[s.nextID] = &playlist{account: accountID, name: name}
	return s.nextID, nil
}

func (s *Store) owned(accountID, playlistID int64) *playlist {
	if p := s.playlists[playlistID]; p != nil && p.account == accountID {
		return p
	}
	return nil
}

func (s *Store) DeletePlaylist(_ context.Context, accountID, playlistID int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.owned(accountID, playlistID) != nil {
		delete(s.playlists, playlistID)
	}
	return nil
}

func (s *Store) Playlist(_ context.Context, accountID, playlistID int64) (*library.Playlist, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	p := s.owned(accountID, playlistID)
	if p == nil {
		return nil, nil
	}
	return &library.Playlist{ID: playlistID, Name: p.name, Items: slices.Clone(p.items)}, nil
}

func (s *Store) AppendItems(_ context.Context, accountID, playlistID int64, items []library.Item) (bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	p := s.owned(accountID, playlistID)
	if p == nil {
		return false, nil
	}
	for _, item := range items {
		if !slices.ContainsFunc(p.items, func(i library.Item) bool { return i.VideoID == item.VideoID }) {
			p.items = append(p.items, item)
		}
	}
	return true, nil
}

func (s *Store) RemoveItem(_ context.Context, accountID, playlistID int64, videoID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if p := s.owned(accountID, playlistID); p != nil {
		p.items = slices.DeleteFunc(p.items, func(i library.Item) bool { return i.VideoID == videoID })
	}
	return nil
}
