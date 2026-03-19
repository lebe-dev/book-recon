package domain

import (
	"context"
	"time"
)

type UserSettingsRepository interface {
	Get(ctx context.Context, telegramID int64) (*UserSettings, error)
	Save(ctx context.Context, settings *UserSettings) error
}

type UserRepository interface {
	Register(ctx context.Context, telegramID int64, username string) error
}

// SearchCacheRepository stores search results for pagination and download.
type SearchCacheRepository interface {
	// Save stores search results. Overwrites previous results for the user.
	Save(ctx context.Context, session *SearchSession) error

	// Get returns saved search results for a user.
	Get(ctx context.Context, telegramID int64) (*SearchSession, error)

	// FindResult looks up a specific result by ID in the user's session.
	FindResult(ctx context.Context, telegramID int64, resultID string) (*SearchResult, error)

	// DeleteExpired removes sessions older than the given TTL.
	DeleteExpired(ctx context.Context, ttl time.Duration) error
}
