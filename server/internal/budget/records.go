package budget

import (
	"context"
	"time"
)

// Repository is where budgets are kept.
type Repository interface {
	// Within gives fn the account's records, held so no other change to them interleaves.
	// fn's writes all apply or none do. It returns ErrNoAccount for an unknown account.
	Within(ctx context.Context, accountID int64, fn func(Records) error) error

	// Plain reads of finished records, which don't need holding.

	// DayTotals is the seconds charged on each budget day from start to end, inclusive,
	// leaving out days with none.
	DayTotals(ctx context.Context, accountID int64, start, end string) (map[string]float64, error)
	// SearchWatched finds watched videos whose title or channel contains the text, ignoring
	// case, newest first. A video appears once per day watched.
	SearchWatched(ctx context.Context, accountID int64, q WatchedQuery) ([]WatchedVideo, error)
}

// Records is one account's budget, held for the length of a Within call. Days are budget
// days as YYYY-MM-DD.
type Records interface {
	// SavedSettings are the settings ever changed. The rest are still at their seeds.
	SavedSettings() (map[string]int, error)
	SaveSetting(key string, value int) error
	// PendingChanges are ordered by when they apply, oldest first.
	PendingChanges() ([]PendingChange, error)
	// QueueChange stores a pending change. Its ID is assigned.
	QueueChange(PendingChange) error
	DeletePendingChange(id int64) error

	// LatestSession is the day's newest session, or nil.
	LatestSession(day string) (*Session, error)
	// CreateSession stores a session. Its ID and LastPlayedAt are ignored.
	CreateSession(Session) error
	SessionVideos(sessionID int64) ([]SessionVideo, error)
	// Plays are this video's appearances in the day's sessions, newest session first.
	Plays(day, videoID string) ([]SessionVideo, error)
	// SavePlay notes a check-in for a video in a session: the first sets FirstAt, every one
	// sets LastAt.
	SavePlay(sessionID int64, videoID string, at time.Time) error
	// LastPlayedAt is the latest check-in in any session, or nil.
	LastPlayedAt() (*time.Time, error)

	// UsedSeconds is the total charged on a day.
	UsedSeconds(day string) (float64, error)
	// Watched is what's been charged for a video on a day, or nil.
	Watched(day, videoID string) (*Watched, error)
	// SaveWatched stores the record for its day and video, replacing any earlier one.
	SaveWatched(Watched) error
}
