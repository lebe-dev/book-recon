package flibustav2

import (
	"context"
	"encoding/xml"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"

	"github.com/charmbracelet/log"
)

// Format represents a book file format.
type Format string

const (
	FormatFB2  Format = "fb2"
	FormatEPUB Format = "epub"
	FormatMOBI Format = "mobi"
	FormatPDF  Format = "pdf"
	FormatTXT  Format = "txt"
	FormatRTF  Format = "rtf"
	FormatHTML Format = "html"
)

// SearchResult holds information about a single book found by search.
type SearchResult struct {
	ID       int      // Flibusta book ID (e.g. 66372)
	Title    string   // Book title
	Authors  []string // Author names
	Formats  []Format // Available download formats
	Language string   // Language code (e.g. "ru", "en")
	Genre    string   // Genre label
}

// BookProvider is the interface every book source must implement.
type BookProvider interface {
	Name() string
	Search(ctx context.Context, query string, limit int) ([]SearchResult, error)
	Download(ctx context.Context, result SearchResult, format Format) (io.ReadCloser, string, error)
}

// -------------------------------------------------------------------
// OPDS Atom XML structures
// -------------------------------------------------------------------

type opdsFeed struct {
	XMLName xml.Name    `xml:"feed"`
	Links   []opdsLink  `xml:"link"`
	Entries []opdsEntry `xml:"entry"`
}

type opdsLink struct {
	Rel  string `xml:"rel,attr"`
	Href string `xml:"href,attr"`
	Type string `xml:"type,attr"`
}

type opdsEntry struct {
	Title      string         `xml:"title"`
	ID         string         `xml:"id"`
	Authors    []opdsAuthor   `xml:"author"`
	Links      []opdsLink     `xml:"link"`
	Categories []opdsCategory `xml:"category"`
	Language   string         `xml:"language"`
	Format     string         `xml:"format"`
}

type opdsAuthor struct {
	Name string `xml:"name"`
	URI  string `xml:"uri"`
}

type opdsCategory struct {
	Label string `xml:"label,attr"`
	Term  string `xml:"term,attr"`
}

// -------------------------------------------------------------------
// Flibusta provider
// -------------------------------------------------------------------

const (
	defaultBaseURL = "http://flibusta.site"
	opdsSearchPath = "/opds/opensearch"
	acquisitionRel = "http://opds-spec.org/acquisition/open-access"
	alternateRel   = "alternate"
)

// mimeToFormat maps OPDS MIME types to Format constants.
var mimeToFormat = map[string]Format{
	"application/fb2+zip":            FormatFB2,
	"application/epub+zip":           FormatEPUB,
	"application/x-mobipocket-ebook": FormatMOBI,
	"application/pdf":                FormatPDF,
	"application/txt+zip":            FormatTXT,
	"application/rtf+zip":            FormatRTF,
	"application/html+zip":           FormatHTML,
}

// bookIDRe extracts the numeric book ID from paths like /b/66372 or /b/66372/fb2.
var bookIDRe = regexp.MustCompile(`/b/(\d+)`)

// Provider implements BookProvider for Flibusta via OPDS.
type Provider struct {
	baseURL    string
	httpClient *http.Client
	logger     *log.Logger
}

// Option configures the Provider.
type Option func(*Provider)

// WithBaseURL sets a custom base URL (useful for testing or mirrors).
func WithBaseURL(u string) Option {
	return func(p *Provider) { p.baseURL = strings.TrimRight(u, "/") }
}

// WithHTTPClient sets a custom HTTP client.
func WithHTTPClient(c *http.Client) Option {
	return func(p *Provider) { p.httpClient = c }
}

// WithLogger sets a logger for the provider.
func WithLogger(l *log.Logger) Option {
	return func(p *Provider) { p.logger = l }
}

// New creates a new Flibusta OPDS BookProvider.
func New(opts ...Option) *Provider {
	p := &Provider{
		baseURL:    defaultBaseURL,
		httpClient: http.DefaultClient,
		logger:     log.Default(),
	}
	for _, o := range opts {
		o(p)
	}
	return p
}

// Name returns the human-readable provider name.
func (p *Provider) Name() string { return "Flibusta" }

// Search finds books by query via the OPDS catalog.
// It fetches as many pages as needed to collect up to limit results.
func (p *Provider) Search(ctx context.Context, query string, limit int) ([]SearchResult, error) {
	if limit <= 0 {
		return nil, nil
	}

	p.logger.Debug("search started", "query", query, "limit", limit)

	var results []SearchResult
	page := 0

	for len(results) < limit {
		feed, err := p.fetchSearchPage(ctx, query, page)
		if err != nil {
			p.logger.Error("search page fetch failed", "query", query, "page", page, "error", err)
			return results, fmt.Errorf("flibusta: search page %d: %w", page, err)
		}
		if len(feed.Entries) == 0 {
			break
		}

		for _, entry := range feed.Entries {
			if len(results) >= limit {
				break
			}
			sr, err := parseEntry(entry)
			if err != nil {
				p.logger.Debug("skipping unparseable entry", "title", entry.Title, "error", err)
				continue
			}
			results = append(results, sr)
		}

		// If the feed has no "next" link, we've reached the last page.
		if !hasNextPage(feed) {
			break
		}
		page++
	}

	p.logger.Info("search completed", "query", query, "results", len(results))
	return results, nil
}

// Download fetches a book file in the requested format.
// Returns the response body (caller must close), the suggested filename,
// and any error.
func (p *Provider) Download(ctx context.Context, result SearchResult, format Format) (io.ReadCloser, string, error) {
	if result.ID == 0 {
		return nil, "", fmt.Errorf("flibusta: book ID is zero")
	}
	if !result.HasFormat(format) {
		return nil, "", fmt.Errorf("flibusta: format %s is not available for book %d", format, result.ID)
	}

	p.logger.Debug("downloading book", "book_id", result.ID, "format", format)

	dlURL := fmt.Sprintf("%s/b/%d/%s", p.baseURL, result.ID, format)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, dlURL, nil)
	if err != nil {
		return nil, "", fmt.Errorf("flibusta: create download request: %w", err)
	}

	resp, err := p.httpClient.Do(req)
	if err != nil {
		p.logger.Error("download request failed", "book_id", result.ID, "format", format, "error", err)
		return nil, "", fmt.Errorf("flibusta: download: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		_ = resp.Body.Close()
		p.logger.Error("download bad status", "book_id", result.ID, "format", format, "status", resp.StatusCode)
		return nil, "", fmt.Errorf("flibusta: download returned status %d", resp.StatusCode)
	}

	filename := buildFilename(result, format, resp)
	p.logger.Info("download ready", "book_id", result.ID, "format", format, "filename", filename)
	return resp.Body, filename, nil
}

// -------------------------------------------------------------------
// Helpers
// -------------------------------------------------------------------

// HasFormat reports whether the search result includes the given format.
func (sr SearchResult) HasFormat(f Format) bool {
	for _, ff := range sr.Formats {
		if ff == f {
			return true
		}
	}
	return false
}

// fetchSearchPage retrieves one page of OPDS search results.
func (p *Provider) fetchSearchPage(ctx context.Context, query string, page int) (*opdsFeed, error) {
	u := fmt.Sprintf("%s%s?searchTerm=%s&searchType=books&pageNumber=%d",
		p.baseURL, opdsSearchPath, url.QueryEscape(query), page)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/atom+xml")

	resp, err := p.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}

	var feed opdsFeed
	if err := xml.NewDecoder(resp.Body).Decode(&feed); err != nil {
		return nil, fmt.Errorf("decode OPDS: %w", err)
	}
	return &feed, nil
}

// parseEntry converts an OPDS entry into a SearchResult.
func parseEntry(e opdsEntry) (SearchResult, error) {
	var sr SearchResult
	sr.Title = e.Title

	// Extract authors.
	for _, a := range e.Authors {
		if name := strings.TrimSpace(a.Name); name != "" {
			sr.Authors = append(sr.Authors, name)
		}
	}

	// Extract book ID and formats from acquisition links.
	for _, l := range e.Links {
		if l.Rel == acquisitionRel {
			// Extract book ID if not yet known.
			if sr.ID == 0 {
				if m := bookIDRe.FindStringSubmatch(l.Href); len(m) == 2 {
					id, _ := strconv.Atoi(m[1])
					sr.ID = id
				}
			}
			if f, ok := mimeToFormat[l.Type]; ok {
				sr.Formats = append(sr.Formats, f)
			}
		}
		// Fallback: try to get book ID from the alternate link.
		if l.Rel == alternateRel && sr.ID == 0 {
			if m := bookIDRe.FindStringSubmatch(l.Href); len(m) == 2 {
				id, _ := strconv.Atoi(m[1])
				sr.ID = id
			}
		}
	}

	if sr.ID == 0 {
		return sr, fmt.Errorf("no book ID found")
	}

	// Genre
	if len(e.Categories) > 0 {
		sr.Genre = e.Categories[0].Label
	}

	// Language
	sr.Language = e.Language

	return sr, nil
}

// hasNextPage returns true if the feed contains a "next" pagination link.
func hasNextPage(feed *opdsFeed) bool {
	for _, l := range feed.Links {
		if l.Rel == "next" {
			return true
		}
	}
	return false
}

// buildFilename creates a reasonable filename from the search result, format,
// and (optionally) the Content-Disposition header.
func buildFilename(sr SearchResult, format Format, resp *http.Response) string {
	// Try Content-Disposition first.
	if cd := resp.Header.Get("Content-Disposition"); cd != "" {
		if fn := extractFilenameFromCD(cd); fn != "" {
			return fn
		}
	}

	// Fallback: build from title.
	safe := sanitizeFilename(sr.Title)
	if safe == "" {
		safe = fmt.Sprintf("book_%d", sr.ID)
	}

	ext := string(format)
	// Some formats are returned as zip archives.
	switch format {
	case FormatFB2, FormatHTML, FormatTXT, FormatRTF:
		ext = ext + ".zip"
	}
	return safe + "." + ext
}

// extractFilenameFromCD tries to pull a filename from a Content-Disposition header.
// Handles both filename="..." and filename*=UTF-8”... forms.
func extractFilenameFromCD(cd string) string {
	re := regexp.MustCompile(`filename\*?=(?:UTF-8''|"?)([^";]+)"?`)
	if m := re.FindStringSubmatch(cd); len(m) == 2 {
		name, err := url.PathUnescape(m[1])
		if err != nil {
			return m[1]
		}
		return name
	}
	return ""
}

// sanitizeFilename removes or replaces characters that are unsafe in filenames.
func sanitizeFilename(s string) string {
	s = strings.Map(func(r rune) rune {
		switch r {
		case '/', '\\', ':', '*', '?', '"', '<', '>', '|':
			return '_'
		default:
			return r
		}
	}, s)
	return strings.TrimSpace(s)
}
