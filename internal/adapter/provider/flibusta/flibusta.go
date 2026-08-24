package flibusta

import (
	"archive/zip"
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
	"github.com/lebe-dev/book-recon/internal/encoding"
	"golang.org/x/net/html"
)

const (
	defaultBaseURL = "https://flibusta.is"
	providerName   = "Flibusta"

	// maxAuthorsToFollow limits how many author pages are fetched when a query
	// matches authors but no book titles.
	maxAuthorsToFollow = 3

	dialTimeout           = 5 * time.Second
	tlsHandshakeTimeout   = 5 * time.Second
	responseHeaderTimeout = 12 * time.Second
	idleConnTimeout       = 60 * time.Second
)

// newHTTPClient builds a client with per-phase timeouts. The overall deadline
// comes from the request context, so downloads of large files are not cut off.
func newHTTPClient() *http.Client {
	return &http.Client{
		Transport: &http.Transport{
			DialContext: (&net.Dialer{
				Timeout:   dialTimeout,
				KeepAlive: 30 * time.Second,
			}).DialContext,
			TLSHandshakeTimeout:   tlsHandshakeTimeout,
			ResponseHeaderTimeout: responseHeaderTimeout,
			IdleConnTimeout:       idleConnTimeout,
			MaxIdleConns:          10,
			ForceAttemptHTTP2:     true,
		},
	}
}

// bookIDRe extracts the numeric book ID from a URL like /b/435845.
var bookIDRe = regexp.MustCompile(`/b/(\d+)`)

// bookPathRe matches a plain book link like /b/435845 (no format suffix).
var bookPathRe = regexp.MustCompile(`^/b/(\d+)/?$`)

// bookFileRe matches a book action link like /b/435845/fb2 or /b/435845/read.
var bookFileRe = regexp.MustCompile(`^/b/(\d+)/[a-z0-9]+`)

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
		client:    newHTTPClient(),
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
//
// Flibusta answers a query with two independent blocks: matched book titles and
// matched authors. When the query names an author rather than a title, only the
// author block is present, so we follow the author links and collect their books.
func (p *Provider) Search(ctx context.Context, query string, limit int) ([]domain.SearchResult, error) {
	if limit <= 0 {
		return nil, nil
	}

	searchURL := p.baseURL + "/booksearch?ask=" + url.QueryEscape(query)

	body, err := p.fetch(ctx, searchURL, "search")
	if err != nil {
		return nil, err
	}

	raw, authors, err := parseSearchPage(body, limit)
	_ = body.Close()
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeProviderError, "parse search results", err)
	}

	if len(raw) == 0 && len(authors) > 0 {
		raw = p.booksByAuthors(ctx, authors, limit)
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

// fetch performs a GET request and returns the response body on HTTP 200.
// The caller must close the returned body.
func (p *Provider) fetch(ctx context.Context, rawURL, stage string) (io.ReadCloser, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, rawURL, nil)
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeProviderError, "build "+stage+" request", err)
	}
	p.setHeaders(req)

	resp, err := p.client.Do(req)
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeProviderError, stage+" request", err)
	}

	if resp.StatusCode != http.StatusOK {
		_ = resp.Body.Close()
		return nil, domain.NewError(domain.ErrCodeProviderError,
			fmt.Sprintf("%s returned status %d", stage, resp.StatusCode))
	}
	return resp.Body, nil
}

// booksByAuthors fetches the pages of the first matched authors and collects
// their books. A failing author page is logged and skipped: partial results
// are better than none.
func (p *Provider) booksByAuthors(ctx context.Context, authors []authorEntry, limit int) []searchEntry {
	var collected []searchEntry

	for i, a := range authors {
		if i >= maxAuthorsToFollow || len(collected) >= limit {
			break
		}

		p.logger.Debug("following author page", "provider", providerName, "author", a.name, "url", a.url)

		body, err := p.fetch(ctx, p.baseURL+a.url, "author page")
		if err != nil {
			p.logger.Warn("author page fetch failed", "provider", providerName, "author", a.name, "error", err)
			continue
		}

		entries, err := parseAuthorBooks(body, a.name, limit-len(collected))
		_ = body.Close()
		if err != nil {
			p.logger.Warn("author page parse failed", "provider", providerName, "author", a.name, "error", err)
			continue
		}

		collected = append(collected, entries...)
	}

	return collected
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

// authorEntry holds one author matched by a search query.
type authorEntry struct {
	name string
	url  string
}

// parseSearchResults parses the flibusta search response HTML and returns up to limit book entries.
//
// Expected structure:
//
//	<h3>Найденные книги:</h3>
//	<ul>
//	  <li><a href="/b/435845">Title</a> - <a href="/a/29081">Author</a></li>
//	</ul>
func parseSearchResults(r io.Reader, limit int) ([]searchEntry, error) {
	books, _, err := parseSearchPage(r, limit)
	return books, err
}

// parseSearchPage parses a flibusta search page and returns both blocks it may
// contain: matched books and matched authors. Authors are only reported when no
// book matched, since the book block is what the caller wants whenever present.
//
// The author block looks like:
//
//	<h3>Найденные писатели (1 - 1 из 1):</h3>
//	<ul><li><a href="/a/151853">Шинзен Янг</a> (1 книга)</li></ul>
func parseSearchPage(r io.Reader, limit int) ([]searchEntry, []authorEntry, error) {
	doc, err := html.Parse(r)
	if err != nil {
		return nil, nil, fmt.Errorf("parse HTML: %w", err)
	}

	var books []searchEntry
	for _, li := range headingListItems(doc, "Найденные книги") {
		if len(books) >= limit {
			break
		}
		if entry := parseLiEntry(li); entry.title != "" {
			books = append(books, entry)
		}
	}
	if len(books) > 0 {
		return books, nil, nil
	}

	var authors []authorEntry
	for _, li := range headingListItems(doc, "Найденные писатели") {
		if len(authors) >= maxAuthorsToFollow {
			break
		}
		if entry := parseAuthorLi(li); entry.url != "" {
			authors = append(authors, entry)
		}
	}
	return nil, authors, nil
}

// headingListItems finds the <h3> with the given text and returns the <li>
// elements of the <ul> that follows it.
func headingListItems(doc *html.Node, heading string) []*html.Node {
	h3 := findHeading(doc, heading)
	if h3 == nil {
		return nil
	}

	ul := findNextSiblingByTag(h3, "ul")
	if ul == nil {
		return nil
	}

	var items []*html.Node
	for li := ul.FirstChild; li != nil; li = li.NextSibling {
		if li.Type == html.ElementNode && li.Data == "li" {
			items = append(items, li)
		}
	}
	return items
}

// parseAuthorBooks extracts the books of one author from their page.
//
// The page also links books from unrelated blocks (comments, new arrivals), so
// only links backed by download links — /b/<id>/fb2, /b/<id>/read and friends —
// count as the author's own books.
func parseAuthorBooks(r io.Reader, author string, limit int) ([]searchEntry, error) {
	if limit <= 0 {
		return nil, nil
	}

	doc, err := html.Parse(r)
	if err != nil {
		return nil, fmt.Errorf("parse HTML: %w", err)
	}

	titles := make(map[string]string)
	downloadable := make(map[string]bool)
	var order []string

	var walk func(*html.Node)
	walk = func(n *html.Node) {
		if n.Type == html.ElementNode && n.Data == "a" {
			href := attrVal(n, "href")

			if m := bookPathRe.FindStringSubmatch(href); len(m) == 2 {
				if _, seen := titles[m[1]]; !seen {
					if title := cleanTitle(textContent(n)); title != "" {
						titles[m[1]] = title
						order = append(order, m[1])
					}
				}
			} else if m := bookFileRe.FindStringSubmatch(href); len(m) == 2 {
				downloadable[m[1]] = true
			}
		}
		for c := n.FirstChild; c != nil; c = c.NextSibling {
			walk(c)
		}
	}
	walk(doc)

	var results []searchEntry
	for _, id := range order {
		if len(results) >= limit {
			break
		}
		if !downloadable[id] {
			continue
		}

		results = append(results, searchEntry{
			title:   titles[id],
			author:  author,
			bookURL: "/b/" + id,
		})
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

// parseAuthorLi extracts one author from an <li> of the "Найденные писатели" block.
func parseAuthorLi(li *html.Node) authorEntry {
	for c := li.FirstChild; c != nil; c = c.NextSibling {
		if c.Type != html.ElementNode || c.Data != "a" {
			continue
		}

		href := attrVal(c, "href")
		if !strings.HasPrefix(href, "/a/") {
			continue
		}

		name := normalizeSpaces(textContent(c))
		if name == "" {
			continue
		}
		return authorEntry{name: name, url: href}
	}
	return authorEntry{}
}

// normalizeSpaces collapses whitespace runs into single spaces.
func normalizeSpaces(s string) string {
	return strings.Join(strings.Fields(s), " ")
}

// cleanTitle removes alternative title suffixes like " [= Alt Title]".
func cleanTitle(s string) string {
	if before, _, found := strings.Cut(s, " [="); found {
		return strings.TrimSpace(before)
	}
	return s
}

// findHeading finds the <h3> whose text contains the given substring.
func findHeading(n *html.Node, text string) *html.Node {
	if n.Type == html.ElementNode && n.Data == "h3" {
		if strings.Contains(textContent(n), text) {
			return n
		}
	}
	for c := n.FirstChild; c != nil; c = c.NextSibling {
		if found := findHeading(c, text); found != nil {
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
