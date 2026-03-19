package domain

import (
	"context"
	"io"
)

// BookProvider is the interface every book source must implement.
// To add a new provider, implement this interface and register it in the composition root.
type BookProvider interface {
	// Name returns the provider name (shown to the user).
	Name() string

	// Search finds books by query (title or author).
	// Returns up to limit results.
	Search(ctx context.Context, query string, limit int) ([]SearchResult, error)

	// Download fetches a book in the given format.
	// Returns a reader with file contents and a filename.
	// The caller is responsible for closing the reader.
	Download(ctx context.Context, result SearchResult, format Format) (io.ReadCloser, string, error)
}
