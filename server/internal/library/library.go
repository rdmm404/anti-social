// Package library keeps an account's subscriptions and playlists. Reading them never needs
// YouTube: clients fetch what a channel or video holds themselves.
package library

import (
	"context"
	"regexp"
	"time"

	"github.com/rdmm404/anti-social/server/internal/apperr"
	"github.com/rdmm404/anti-social/server/internal/text"
)

const (
	MaxName  = 100
	MaxTitle = 200
)

var (
	videoID   = regexp.MustCompile(`^[\w-]{11}$`)
	channelID = regexp.MustCompile(`^(UC[\w-]{20,}|@[\w.-]+)$`)
)

type Subscription struct {
	ChannelID string
	Name      string
}

type PlaylistSummary struct {
	ID    int64
	Name  string
	Count int
}

type Item struct {
	VideoID  string
	Title    string
	Channel  string
	Duration *int // seconds, when known
}

type Playlist struct {
	ID    int64
	Name  string
	Items []Item
}

// Store holds the library. Every method is scoped to one account.
type Store interface {
	Subscriptions(ctx context.Context, accountID int64) ([]Subscription, error)
	// SaveSubscription adds a channel, or renames it if it's already there.
	SaveSubscription(ctx context.Context, accountID int64, s Subscription, at time.Time) error
	DeleteSubscription(ctx context.Context, accountID int64, channelID string) error

	Playlists(ctx context.Context, accountID int64) ([]PlaylistSummary, error)
	CreatePlaylist(ctx context.Context, accountID int64, name string, at time.Time) (int64, error)
	DeletePlaylist(ctx context.Context, accountID, playlistID int64) error
	// Playlist returns nil when the account has no such playlist.
	Playlist(ctx context.Context, accountID, playlistID int64) (*Playlist, error)
	// AppendItems adds videos at the end in order, skipping any already in the list. It
	// returns false when the account has no such playlist.
	AppendItems(ctx context.Context, accountID, playlistID int64, items []Item) (bool, error)
	RemoveItem(ctx context.Context, accountID, playlistID int64, videoID string) error
}

type Service struct {
	store Store
	clock func() time.Time
}

func NewService(store Store, clock func() time.Time) *Service {
	if clock == nil {
		clock = time.Now
	}
	return &Service{store, clock}
}

func (s *Service) Subscriptions(ctx context.Context, accountID int64) ([]Subscription, error) {
	return s.store.Subscriptions(ctx, accountID)
}

func (s *Service) Subscribe(ctx context.Context, accountID int64, sub Subscription) error {
	if !channelID.MatchString(sub.ChannelID) {
		return apperr.New(apperr.Invalid, "Not a channel id.")
	}
	sub.Name = text.Clip(sub.Name, MaxTitle)
	return s.store.SaveSubscription(ctx, accountID, sub, s.clock())
}

func (s *Service) Unsubscribe(ctx context.Context, accountID int64, channelID string) error {
	return s.store.DeleteSubscription(ctx, accountID, channelID)
}

func (s *Service) Playlists(ctx context.Context, accountID int64) ([]PlaylistSummary, error) {
	return s.store.Playlists(ctx, accountID)
}

func (s *Service) CreatePlaylist(ctx context.Context, accountID int64, name string) (int64, error) {
	name = text.Clip(name, MaxName)
	if name == "" {
		return 0, apperr.New(apperr.Invalid, "Give the playlist a name.")
	}
	return s.store.CreatePlaylist(ctx, accountID, name, s.clock())
}

func (s *Service) DeletePlaylist(ctx context.Context, accountID, playlistID int64) error {
	return s.store.DeletePlaylist(ctx, accountID, playlistID)
}

var errNoPlaylist = apperr.New(apperr.NotFound, "That playlist no longer exists.")

func (s *Service) Playlist(ctx context.Context, accountID, playlistID int64) (Playlist, error) {
	p, err := s.store.Playlist(ctx, accountID, playlistID)
	if err != nil {
		return Playlist{}, err
	}
	if p == nil {
		return Playlist{}, errNoPlaylist
	}
	return *p, nil
}

func (s *Service) AddToPlaylist(ctx context.Context, accountID, playlistID int64, items []Item) error {
	for i, item := range items {
		if !videoID.MatchString(item.VideoID) {
			return apperr.New(apperr.Invalid, "Not a video id.")
		}
		items[i].Title = text.Clip(item.Title, MaxTitle)
		items[i].Channel = text.Clip(item.Channel, MaxTitle)
	}
	found, err := s.store.AppendItems(ctx, accountID, playlistID, items)
	if err == nil && !found {
		return errNoPlaylist
	}
	return err
}

func (s *Service) RemoveFromPlaylist(ctx context.Context, accountID, playlistID int64, videoID string) error {
	return s.store.RemoveItem(ctx, accountID, playlistID, videoID)
}
