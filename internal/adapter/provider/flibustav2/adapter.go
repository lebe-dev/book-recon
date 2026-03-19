package flibustav2

import (
	"context"
	"fmt"
	"io"
	"strconv"
	"strings"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

const providerName = "Flibusta"

// DomainProvider wraps the OPDS Provider and implements domain.BookProvider.
type DomainProvider struct {
	inner *Provider
}

// NewDomainProvider creates a domain.BookProvider backed by the OPDS engine.
func NewDomainProvider(baseURL string, logger *log.Logger) *DomainProvider {
	opts := []Option{WithLogger(logger)}
	if baseURL != "" {
		opts = append(opts, WithBaseURL(baseURL))
	}
	return &DomainProvider{inner: New(opts...)}
}

func (p *DomainProvider) Name() string { return providerName }

// Search implements domain.BookProvider.
func (p *DomainProvider) Search(ctx context.Context, query string, limit int) ([]domain.SearchResult, error) {
	results, err := p.inner.Search(ctx, query, limit)
	if err != nil {
		return nil, err
	}

	out := make([]domain.SearchResult, 0, len(results))
	for _, r := range results {
		book := domain.Book{
			Title:     r.Title,
			Author:    strings.Join(r.Authors, ", "),
			Formats:   toDomainFormats(r.Formats),
			Provider:  providerName,
			SourceURL: fmt.Sprintf("/b/%d", r.ID),
		}
		out = append(out, domain.NewSearchResult(book))
	}
	return out, nil
}

// Download implements domain.BookProvider.
func (p *DomainProvider) Download(ctx context.Context, result domain.SearchResult, format domain.Format) (io.ReadCloser, string, error) {
	bookID := extractIDFromURL(result.Book.SourceURL)
	if bookID == 0 {
		return nil, "", domain.NewError(domain.ErrCodeProviderError, "cannot extract book ID from URL")
	}

	sr := SearchResult{
		ID:      bookID,
		Title:   result.Book.Title,
		Formats: toV2Formats(result.Book.Formats),
	}

	v2fmt := toV2Format(format)
	return p.inner.Download(ctx, sr, v2fmt)
}

// -------------------------------------------------------------------
// Type conversion helpers
// -------------------------------------------------------------------

func toDomainFormats(formats []Format) []domain.Format {
	out := make([]domain.Format, 0, len(formats))
	for _, f := range formats {
		switch f {
		case FormatFB2:
			out = append(out, domain.FormatFB2)
		case FormatEPUB:
			out = append(out, domain.FormatEPUB)
		}
	}
	return out
}

func toV2Formats(formats []domain.Format) []Format {
	out := make([]Format, 0, len(formats))
	for _, f := range formats {
		out = append(out, toV2Format(f))
	}
	return out
}

func toV2Format(f domain.Format) Format {
	switch f {
	case domain.FormatEPUB:
		return FormatEPUB
	default:
		return FormatFB2
	}
}

func extractIDFromURL(sourceURL string) int {
	m := bookIDRe.FindStringSubmatch(sourceURL)
	if len(m) != 2 {
		return 0
	}
	id, _ := strconv.Atoi(m[1])
	return id
}
