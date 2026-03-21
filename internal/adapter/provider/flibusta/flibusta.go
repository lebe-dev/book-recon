package flibusta

import (
	"archive/zip"
	"bytes"
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strings"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
	"github.com/lebe-dev/book-recon/internal/encoding"
	"golang.org/x/net/html"
)

const (
	defaultBaseURL = "https://flibusta.is"
	providerName   = "Flibusta"
)

// bookIDRe extracts the numeric book ID from a URL like /b/435845.
var bookIDRe = regexp.MustCompile(`/b/(\d+)`)

// Provider implements domain.BookProvider for flibusta.is.
type Provider struct {
	client    *http.Client
	baseURL   string
	userAgent string
	logger    *log.Logger
}

// New creates a new flibusta provider with default HTTP client.
func New(baseURL, userAgent string, logger *log.Logger) *Provider {
	if baseURL == "" {
		baseURL = defaultBaseURL
	}
	return &Provider{
		client:    http.DefaultClient,
		baseURL:   baseURL,
		userAgent: userAgent,
		logger:    logger,
	}
}

// newWithBaseURL creates a provider pointing at a custom base URL (for tests).
func newWithBaseURL(baseURL string, client *http.Client, logger *log.Logger) *Provider {
	return &Provider{client: client, baseURL: baseURL, logger: logger}
}

func (p *Provider) setHeaders(req *http.Request) {
	if p.userAgent != "" {
		req.Header.Set("User-Agent", p.userAgent)
	}
}

func (p *Provider) Name() string {
	return providerName
}

// Search finds books by query. Returns up to limit results.
func (p *Provider) Search(ctx context.Context, query string, limit int) ([]domain.SearchResult, error) {
	if limit <= 0 {
		return nil, nil
	}

	searchURL := p.baseURL + "/booksearch?ask=" + url.QueryEscape(query)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, searchURL, nil)
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeProviderError, "build search request", err)
	}
	p.setHeaders(req)

	resp, err := p.client.Do(req)
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeProviderError, "search request", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return nil, domain.NewError(domain.ErrCodeProviderError,
			fmt.Sprintf("search returned status %d", resp.StatusCode))
	}

	raw, err := parseSearchResults(resp.Body, limit)
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeProviderError, "parse search results", err)
	}

	results := make([]domain.SearchResult, 0, len(raw))
	for _, r := range raw {
		book := domain.Book{
			Title:     r.title,
			Author:    r.author,
			Formats:   []domain.Format{domain.FormatFB2, domain.FormatEPUB, domain.FormatMOBI},
			Provider:  providerName,
			SourceURL: r.bookURL,
		}
		results = append(results, domain.NewSearchResult(book))
	}

	p.logger.Info("search completed", "provider", providerName, "query", query, "results", len(results))
	return results, nil
}

// Download fetches a book in the given format.
// Flibusta may serve files directly or wrapped in a ZIP archive.
func (p *Provider) Download(ctx context.Context, result domain.SearchResult, format domain.Format) (io.ReadCloser, string, error) {
	bookID := extractBookID(result.Book.SourceURL)
	if bookID == "" {
		return nil, "", domain.NewError(domain.ErrCodeProviderError, "cannot extract book ID from URL")
	}

	downloadURL := fmt.Sprintf("%s/b/%s/%s", p.baseURL, bookID, string(format))
	p.logger.Debug("downloading book", "url", downloadURL, "format", format)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, downloadURL, nil)
	if err != nil {
		return nil, "", domain.WrapError(domain.ErrCodeProviderError, "build download request", err)
	}
	p.setHeaders(req)

	resp, err := p.client.Do(req)
	if err != nil {
		return nil, "", domain.WrapError(domain.ErrCodeProviderError, "download request", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return nil, "", domain.NewError(domain.ErrCodeProviderError,
			fmt.Sprintf("download returned status %d", resp.StatusCode))
	}

	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, "", domain.WrapError(domain.ErrCodeProviderError, "read response body", err)
	}

	// Try to interpret the response as a ZIP archive.
	zr, zipErr := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if zipErr != nil {
		// Not a zip — return body directly.
		filename := encoding.FilenameFromDisposition(resp.Header.Get("Content-Disposition"))
		if filename == "" {
			filename = fallbackFilename(result.Book.Author, result.Book.Title, string(format))
		}
		p.logger.Info("download ready", "provider", providerName, "filename", filename)
		return io.NopCloser(bytes.NewReader(data)), filename, nil
	}

	if len(zr.File) == 0 {
		return nil, "", domain.NewError(domain.ErrCodeProviderError, "zip archive is empty")
	}

	// EPUB files are themselves ZIP archives. If the first entry is "mimetype",
	// return the entire buffer as an .epub file.
	if zr.File[0].Name == "mimetype" {
		filename := fallbackFilename(result.Book.Author, result.Book.Title, "epub")
		p.logger.Info("download ready", "provider", providerName, "filename", filename)
		return io.NopCloser(bytes.NewReader(data)), filename, nil
	}

	f := zr.File[0]
	rc, err := f.Open()
	if err != nil {
		return nil, "", domain.WrapError(domain.ErrCodeProviderError, "open file in zip", err)
	}

	filename := encoding.DecodeZipFilename(f.Name)
	if filename == "" {
		filename = fallbackFilename(result.Book.Author, result.Book.Title, string(format))
	}

	p.logger.Info("download ready", "provider", providerName, "filename", filename)
	return rc, filename, nil
}

// ---------------------------------------------------------------------------
// HTML parsing
// ---------------------------------------------------------------------------

// searchEntry holds raw data extracted from one search result row.
type searchEntry struct {
	title   string
	author  string
	bookURL string
}

// parseSearchResults parses the flibusta search response HTML and returns up to limit entries.
//
// Expected structure:
//
//	<h3>Найденные книги:</h3>
//	<ul>
//	  <li><a href="/b/435845">Title</a> - <a href="/a/29081">Author</a></li>
//	</ul>
func parseSearchResults(r io.Reader, limit int) ([]searchEntry, error) {
	doc, err := html.Parse(r)
	if err != nil {
		return nil, fmt.Errorf("parse HTML: %w", err)
	}

	h3 := findBooksHeading(doc)
	if h3 == nil {
		return nil, nil
	}

	ul := findNextSiblingByTag(h3, "ul")
	if ul == nil {
		return nil, nil
	}

	var results []searchEntry
	for li := ul.FirstChild; li != nil && len(results) < limit; li = li.NextSibling {
		if li.Type != html.ElementNode || li.Data != "li" {
			continue
		}

		entry := parseLiEntry(li)
		if entry.title == "" {
			continue
		}

		results = append(results, entry)
	}

	return results, nil
}

// parseLiEntry extracts book title, URL, and author(s) from a single <li>.
func parseLiEntry(li *html.Node) searchEntry {
	var entry searchEntry
	var authors []string

	for c := li.FirstChild; c != nil; c = c.NextSibling {
		if c.Type != html.ElementNode || c.Data != "a" {
			continue
		}

		href := attrVal(c, "href")
		if bookIDRe.MatchString(href) && entry.title == "" {
			entry.title = cleanTitle(textContent(c))
			entry.bookURL = href
		} else if strings.HasPrefix(href, "/a/") {
			authors = append(authors, textContent(c))
		}
	}

	entry.author = strings.Join(authors, ", ")
	return entry
}

// cleanTitle removes alternative title suffixes like " [= Alt Title]".
func cleanTitle(s string) string {
	if before, _, found := strings.Cut(s, " [="); found {
		return strings.TrimSpace(before)
	}
	return s
}

// findBooksHeading finds <h3> containing "Найденные книги".
func findBooksHeading(n *html.Node) *html.Node {
	if n.Type == html.ElementNode && n.Data == "h3" {
		if strings.Contains(textContent(n), "Найденные книги") {
			return n
		}
	}
	for c := n.FirstChild; c != nil; c = c.NextSibling {
		if found := findBooksHeading(c); found != nil {
			return found
		}
	}
	return nil
}

// findNextSiblingByTag returns the next sibling element with the given tag.
func findNextSiblingByTag(n *html.Node, tag string) *html.Node {
	for s := n.NextSibling; s != nil; s = s.NextSibling {
		if s.Type == html.ElementNode && s.Data == tag {
			return s
		}
	}
	return nil
}

func textContent(n *html.Node) string {
	if n.Type == html.TextNode {
		return n.Data
	}
	var sb strings.Builder
	for c := n.FirstChild; c != nil; c = c.NextSibling {
		sb.WriteString(textContent(c))
	}
	return strings.TrimSpace(sb.String())
}

func attrVal(n *html.Node, name string) string {
	for _, a := range n.Attr {
		if a.Key == name {
			return a.Val
		}
	}
	return ""
}

// extractBookID returns the numeric book ID from a flibusta URL like "/b/435845".
func extractBookID(bookURL string) string {
	if m := bookIDRe.FindStringSubmatch(bookURL); len(m) == 2 {
		return m[1]
	}
	return ""
}

// CheckHealth checks Flibusta availability by issuing an HTTP HEAD request.
func (p *Provider) CheckHealth(ctx context.Context) []domain.HealthStatus {
	req, err := http.NewRequestWithContext(ctx, http.MethodHead, p.baseURL, nil)
	if err != nil {
		return []domain.HealthStatus{{Name: providerName, Healthy: false, Detail: err.Error()}}
	}
	p.setHeaders(req)

	resp, err := p.client.Do(req)
	if err != nil {
		return []domain.HealthStatus{{Name: providerName, Healthy: false, Detail: err.Error()}}
	}
	_ = resp.Body.Close()

	if resp.StatusCode >= 400 {
		return []domain.HealthStatus{{Name: providerName, Healthy: false, Detail: fmt.Sprintf("HTTP %d", resp.StatusCode)}}
	}
	return []domain.HealthStatus{{Name: providerName, Healthy: true, Detail: fmt.Sprintf("HTTP %d", resp.StatusCode)}}
}

// fallbackFilename builds a filename when no better name is available.
func fallbackFilename(author, title, format string) string {
	name := strings.TrimSpace(author + ". " + title)
	if name == ". " {
		name = "book"
	}
	return name + "." + format
}
