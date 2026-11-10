package domain

import "context"

// TrackerKind is what the Android app searches RuTracker for.
type TrackerKind string

const (
	TrackerKindBook  TrackerKind = "book"
	TrackerKindAudio TrackerKind = "audio"
)

// TrackerTopic is a RuTracker topic found for the Android app.
type TrackerTopic struct {
	ID      string
	Title   string
	Size    int64
	Seeders int
	Peers   int
}

// TopicTracker searches RuTracker with the server's own account, so the app needs no
// credentials of its own.
type TopicTracker interface {
	// Search returns topics with seeders, at most limit of them.
	Search(ctx context.Context, query string, kind TrackerKind, limit int) ([]TrackerTopic, error)
	// FetchTorrent returns the .torrent of a topic found by an earlier Search; a topic the
	// tracker has not seen gives ErrCodeNotFound.
	FetchTorrent(ctx context.Context, topicID string) ([]byte, error)
}
