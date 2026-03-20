package domain

import (
	"crypto/sha256"
	"fmt"
	"slices"
	"time"
)

type Format string

const (
	FormatEPUB Format = "epub"
	FormatFB2  Format = "fb2"
	FormatMOBI Format = "mobi"
)

type Book struct {
	Title     string
	Author    string
	Formats   []Format
	Provider  string
	SourceURL string
}

// HasFormat reports whether the book is available in the given format.
func (b Book) HasFormat(f Format) bool {
	return slices.Contains(b.Formats, f)
}

// SearchResult is a single item in search results.
// ID is stable — it survives cache round-trips.
type SearchResult struct {
	ID   string
	Book Book
}

// NewSearchResult creates a SearchResult with a deterministic ID.
func NewSearchResult(book Book) SearchResult {
	raw := book.Provider + book.SourceURL
	hash := sha256.Sum256([]byte(raw))
	id := fmt.Sprintf("%x", hash[:6]) // 12 hex chars
	return SearchResult{ID: id, Book: book}
}

// SearchSession holds cached search results for a user.
type SearchSession struct {
	TelegramID int64
	Results    []SearchResult
	CreatedAt  time.Time
}
