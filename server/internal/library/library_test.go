package library_test

// Each test runs against the in-memory store, and against Postgres too when
// ANTI_SOCIAL_TEST_DATABASE_URL is set.

import (
	"context"
	"reflect"
	"testing"

	"github.com/rdmm404/anti-social/server/internal/accounts"
	accountspg "github.com/rdmm404/anti-social/server/internal/accounts/postgres"
	"github.com/rdmm404/anti-social/server/internal/apperr"
	"github.com/rdmm404/anti-social/server/internal/library"
	"github.com/rdmm404/anti-social/server/internal/library/memory"
	librarypg "github.com/rdmm404/anti-social/server/internal/library/postgres"
	"github.com/rdmm404/anti-social/server/internal/testdb"
)

type fixture struct {
	ctx     context.Context
	lib     *library.Service
	account int64
	other   int64
}

func forEach(t *testing.T, test func(t *testing.T, f fixture)) {
	t.Parallel()
	t.Run("memory", func(t *testing.T) {
		t.Parallel()
		test(t, fixture{context.Background(), library.NewService(memory.New(), nil), 1, 2})
	})
	t.Run("postgres", func(t *testing.T) {
		t.Parallel()
		pool := testdb.Optional(t)
		ctx := context.Background()
		account, err := accounts.NewService(accountspg.New(pool)).EnsureOwner(ctx, map[string]string{"test": "token"})
		if err != nil {
			t.Fatal(err)
		}
		var other int64
		if err := pool.QueryRow(ctx, "INSERT INTO accounts DEFAULT VALUES RETURNING id").Scan(&other); err != nil {
			t.Fatal(err)
		}
		test(t, fixture{ctx, library.NewService(librarypg.New(pool), nil), account, other})
	})
}

func equal[T any](t *testing.T, got, want T) {
	t.Helper()
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %+v, want %+v", got, want)
	}
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

const (
	channel = "UCYO_jab_esuFRV4b17AJtAw"
	video   = "aircAruvnKk"
)

func TestSubscriptions(t *testing.T) {
	forEach(t, func(t *testing.T, f fixture) {
		must(t, f.lib.Subscribe(f.ctx, f.account, library.Subscription{ChannelID: channel, Name: "  3Blue1Brown "}))
		must(t, f.lib.Subscribe(f.ctx, f.account, library.Subscription{ChannelID: "@veritasium", Name: "Veritasium"}))
		must(t, f.lib.Subscribe(f.ctx, f.account, library.Subscription{ChannelID: channel, Name: "3B1B"})) // Renames.
		subs, err := f.lib.Subscriptions(f.ctx, f.account)
		must(t, err)
		equal(t, subs, []library.Subscription{{channel, "3B1B"}, {"@veritasium", "Veritasium"}})

		if err := f.lib.Subscribe(f.ctx, f.account, library.Subscription{ChannelID: "nope"}); !apperr.Is(err, apperr.Invalid) {
			t.Fatalf("got %v, want it called invalid", err)
		}
		others, err := f.lib.Subscriptions(f.ctx, f.other)
		must(t, err)
		equal(t, len(others), 0)

		must(t, f.lib.Unsubscribe(f.ctx, f.account, channel))
		subs, err = f.lib.Subscriptions(f.ctx, f.account)
		must(t, err)
		equal(t, subs, []library.Subscription{{"@veritasium", "Veritasium"}})
	})
}

func TestPlaylists(t *testing.T) {
	forEach(t, func(t *testing.T, f fixture) {
		if _, err := f.lib.CreatePlaylist(f.ctx, f.account, "   "); !apperr.Is(err, apperr.Invalid) {
			t.Fatalf("got %v, want an unnamed playlist called invalid", err)
		}
		id, err := f.lib.CreatePlaylist(f.ctx, f.account, "Later")
		must(t, err)
		duration := 1140
		item := library.Item{VideoID: video, Title: "But what is a neural network?", Channel: "3Blue1Brown", Duration: &duration}
		must(t, f.lib.AddToPlaylist(f.ctx, f.account, id, []library.Item{item, {VideoID: "dQw4w9WgXcQ", Title: "Other"}}))
		must(t, f.lib.AddToPlaylist(f.ctx, f.account, id, []library.Item{{VideoID: video, Title: "Again"}})) // Keeps its place.

		p, err := f.lib.Playlist(f.ctx, f.account, id)
		must(t, err)
		equal(t, p, library.Playlist{ID: id, Name: "Later", Items: []library.Item{item, {VideoID: "dQw4w9WgXcQ", Title: "Other"}}})
		lists, err := f.lib.Playlists(f.ctx, f.account)
		must(t, err)
		equal(t, lists, []library.PlaylistSummary{{id, "Later", 2}})

		// Another account can't see or change it.
		if _, err := f.lib.Playlist(f.ctx, f.other, id); !apperr.Is(err, apperr.NotFound) {
			t.Fatalf("got %v, want not found", err)
		}
		if err := f.lib.AddToPlaylist(f.ctx, f.other, id, []library.Item{{VideoID: video}}); !apperr.Is(err, apperr.NotFound) {
			t.Fatalf("got %v, want not found", err)
		}
		must(t, f.lib.DeletePlaylist(f.ctx, f.other, id))
		must(t, f.lib.RemoveFromPlaylist(f.ctx, f.other, id, video))

		must(t, f.lib.RemoveFromPlaylist(f.ctx, f.account, id, video))
		p, err = f.lib.Playlist(f.ctx, f.account, id)
		must(t, err)
		equal(t, len(p.Items), 1)
		must(t, f.lib.DeletePlaylist(f.ctx, f.account, id))
		if _, err := f.lib.Playlist(f.ctx, f.account, id); !apperr.Is(err, apperr.NotFound) {
			t.Fatalf("got %v, want not found", err)
		}
	})
}
