package flibustav2

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"sync"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

const providerName = "Flibusta"

// DomainProvider wraps one OPDS Provider per mirror and implements domain.BookProvider.
type DomainProvider struct {
	mirrors []*Provider

	mu   sync.Mutex
	last int
}

// NewDomainProvider creates a domain.BookProvider backed by the OPDS engine. baseURLs are
// the mirrors to try in order; empty means the engine's default mirror.
func NewDomainProvider(baseURLs []string, logger *log.Logger) *DomainProvider {
	var mirrors []*Provider
	for _, u := range baseURLs {
		if strings.TrimSpace(u) == "" {
			continue
		}
		mirrors = append(mirrors, New(WithLogger(logger), WithBaseURL(strings.TrimSpace(u))))
	}
	if len(mirrors) == 0 {
		mirrors = append(mirrors, New(WithLogger(logger)))
	}
	return &DomainProvider{mirrors: mirrors}
}

func (p *DomainProvider) Name() string { return providerName }

// order lists mirror indexes in the order to try: the one that answered last goes first.
func (p *DomainProvider) order() []int {
	p.mu.Lock()
	last := p.last
	p.mu.Unlock()

	order := []int{last}
	for i := range p.mirrors {
		if i != last {
			order = append(order, i)
		}
	}
	return order
}

func (p *DomainProvider) remember(i int) {
	p.mu.Lock()
	p.last = i
	p.mu.Unlock()
}

// onMirrors runs block against the mirrors in turn until one answers. Only an unreachable
// mirror moves on to the next one.
func onMirrors[T any](ctx context.Context, p *DomainProvider, block func(m *Provider) (T, error)) (T, error) {
	var zero T
	var lastErr error
	for _, i := range p.order() {
		m := p.mirrors[i]
		result, err := block(m)
		if err == nil {
			p.remember(i)
			return result, nil
		}
		var unreachable *unreachableError
		if !errors.As(err, &unreachable) || ctx.Err() != nil {
			return zero, err
		}
		m.logger.Warn("flibusta mirror is unreachable, trying the next one", "mirror", m.baseURL, "error", err)
		lastErr = err
	}
	return zero, lastErr
}

// Search implements domain.BookProvider.
func (p *DomainProvider) Search(ctx context.Context, query string, limit int) ([]domain.SearchResult, error) {
	results, err := onMirrors(ctx, p, func(m *Provider) ([]SearchResult, error) {
		return m.Search(ctx, query, limit)
	})
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

	type download struct {
		body     io.ReadCloser
		filename string
	}
	v2fmt := toV2Format(format)
	d, err := onMirrors(ctx, p, func(m *Provider) (download, error) {
		body, filename, err := m.Download(ctx, sr, v2fmt)
		return download{body, filename}, err
	})
	if err != nil {
		return nil, "", err
	}
	return d.body, d.filename, nil
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
		case FormatMOBI:
			out = append(out, domain.FormatMOBI)
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
	case domain.FormatMOBI:
		return FormatMOBI
	default:
		return FormatFB2
	}
}

// CheckHealth checks Flibusta availability by issuing an HTTP HEAD request to the
// mirrors in turn: it is healthy when any of them answers.
func (p *DomainProvider) CheckHealth(ctx context.Context) []domain.HealthStatus {
	var last domain.HealthStatus
	for _, i := range p.order() {
		last = checkMirror(ctx, p.mirrors[i])
		if last.Healthy {
			return []domain.HealthStatus{last}
		}
	}
	return []domain.HealthStatus{last}
}

func checkMirror(ctx context.Context, m *Provider) domain.HealthStatus {
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, m.baseURL, nil)
	if err != nil {
		return domain.HealthStatus{Name: providerName, Healthy: false, Detail: err.Error()}
	}

	resp, err := m.httpClient.Do(req)
	if err != nil {
		return domain.HealthStatus{Name: providerName, Healthy: false, Detail: err.Error()}
	}
	_ = resp.Body.Close()

	if resp.StatusCode >= 400 {
		return domain.HealthStatus{Name: providerName, Healthy: false, Detail: fmt.Sprintf("HTTP %d", resp.StatusCode)}
	}
	return domain.HealthStatus{Name: providerName, Healthy: true, Detail: fmt.Sprintf("HTTP %d", resp.StatusCode)}
}

func extractIDFromURL(sourceURL string) int {
	m := bookIDRe.FindStringSubmatch(sourceURL)
	if len(m) != 2 {
		return 0
	}
	id, _ := strconv.Atoi(m[1])
	return id
}
