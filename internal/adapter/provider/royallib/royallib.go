package royallib

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
	"unicode/utf8"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
	"golang.org/x/net/html"
	"golang.org/x/text/encoding/charmap"
)

const (
	defaultBaseURL = "https://royallib.com"
	providerName   = "RoyalLib.com"
)

// bookPathRe extracts {author_slug}/{book_slug} from a book URL like
// //royallib.com/book/kuptsov_vasiliy/a_bila_li_tayna.html
var bookPathRe = regexp.MustCompile(`/book/(.+)\.html`)

// Provider implements domain.BookProvider for royallib.com.
type Provider struct {
	client    *http.Client
	baseURL   string
	userAgent string
	logger    *log.Logger
}

// New creates a new royallib provider with default HTTP client.
func New(userAgent string, logger *log.Logger) *Provider {
	return &Provider{
		client:    http.DefaultClient,
		baseURL:   defaultBaseURL,
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

	searchURL := p.baseURL + "/search/"
	form := url.Values{}
	form.Set("to", "result")
	form.Set("q", query)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, searchURL, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeProviderError, "build search request", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
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
			Formats:   []domain.Format{domain.FormatFB2, domain.FormatEPUB},
			Provider:  providerName,
			SourceURL: r.bookURL,
		}
		results = append(results, domain.NewSearchResult(book))
	}

	p.logger.Info("search completed", "provider", providerName, "query", query, "results", len(results))
	return results, nil
}

// Download fetches a book in the given format and unpacks the ZIP archive.
// Exception: EPUB files are themselves ZIP archives, so royallib sends them
// directly under a .zip URL — in that case the buffer is returned as-is.
func (p *Provider) Download(ctx context.Context, result domain.SearchResult, format domain.Format) (io.ReadCloser, string, error) {
	bookPath := extractBookPath(result.Book.SourceURL)
	if bookPath == "" {
		return nil, "", domain.NewError(domain.ErrCodeProviderError, "cannot extract book path from URL")
	}

	downloadURL := fmt.Sprintf("%s/get/%s/%s.zip", p.baseURL, string(format), bookPath)
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

	zr, err := zip.NewReader(bytes.NewReader(data), int64(len(data)))
	if err != nil {
		return nil, "", domain.WrapError(domain.ErrCodeProviderError, "open zip archive", err)
	}
	if len(zr.File) == 0 {
		return nil, "", domain.NewError(domain.ErrCodeProviderError, "zip archive is empty")
	}

	// EPUB files are themselves ZIP archives. Royallib serves them under a .zip
	// URL without re-wrapping, so the first zip entry is "mimetype" (the EPUB
	// spec marker). Return the entire buffer as an .epub file.
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

	filename := decodeFilename(f.Name)
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
	title     string
	author    string
	bookURL   string
	authorURL string
	bookPath  string
}

// parseSearchResults parses the search response HTML and returns up to limit entries
// from the "Книги" section.
func parseSearchResults(r io.Reader, limit int) ([]searchEntry, error) {
	doc, err := html.Parse(r)
	if err != nil {
		return nil, fmt.Errorf("parse HTML: %w", err)
	}

	h2 := findBooksHeading(doc)
	if h2 == nil {
		return nil, nil
	}

	headerTR := ancestorTag(h2, "tr")
	if headerTR == nil {
		return nil, nil
	}

	var results []searchEntry
	for tr := headerTR.NextSibling; tr != nil && len(results) < limit; tr = tr.NextSibling {
		if tr.Type != html.ElementNode || tr.Data != "tr" {
			continue
		}

		tds := childrenByTag(tr, "td")
		if len(tds) < 2 {
			continue
		}

		bookLink := firstDescendantLink(tds[0])
		authorLink := firstDescendantLink(tds[len(tds)-1])

		if bookLink == nil {
			continue
		}

		href := attrVal(bookLink, "href")
		e := searchEntry{
			title:   textContent(bookLink),
			bookURL: normalizeURL(href),
		}

		if m := bookPathRe.FindStringSubmatch(href); len(m) == 2 {
			e.bookPath = m[1]
		}

		if authorLink != nil {
			e.author = textContent(authorLink)
			e.authorURL = normalizeURL(attrVal(authorLink, "href"))
		}

		results = append(results, e)
	}

	return results, nil
}

func findBooksHeading(n *html.Node) *html.Node {
	if n.Type == html.ElementNode && n.Data == "h2" {
		if strings.TrimSpace(textContent(n)) == "Книги" && ancestorTag(n, "tr") != nil {
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

func ancestorTag(n *html.Node, tag string) *html.Node {
	for p := n.Parent; p != nil; p = p.Parent {
		if p.Type == html.ElementNode && p.Data == tag {
			return p
		}
	}
	return nil
}

func childrenByTag(n *html.Node, tag string) []*html.Node {
	var out []*html.Node
	for c := n.FirstChild; c != nil; c = c.NextSibling {
		if c.Type == html.ElementNode && c.Data == tag {
			out = append(out, c)
		}
	}
	return out
}

func firstDescendantLink(n *html.Node) *html.Node {
	if n.Type == html.ElementNode && n.Data == "a" {
		return n
	}
	for c := n.FirstChild; c != nil; c = c.NextSibling {
		if found := firstDescendantLink(c); found != nil {
			return found
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

func normalizeURL(raw string) string {
	if strings.HasPrefix(raw, "//") {
		return "https:" + raw
	}
	return raw
}

func extractBookPath(bookURL string) string {
	if m := bookPathRe.FindStringSubmatch(bookURL); len(m) == 2 {
		return m[1]
	}
	return ""
}

// ---------------------------------------------------------------------------
// Filename helpers
// ---------------------------------------------------------------------------

// filenameFromDisposition extracts the filename from a Content-Disposition header.
// The resulting string is guaranteed to be valid UTF-8.
func filenameFromDisposition(cd string) string {
	if cd == "" {
		return ""
	}

	// Try filename*= (RFC 5987) first — always UTF-8 by spec.
	if idx := strings.Index(cd, "filename*="); idx != -1 {
		val := cd[idx+len("filename*="):]
		if i := strings.IndexByte(val, ';'); i != -1 {
			val = val[:i]
		}
		val = strings.TrimSpace(val)
		if parts := strings.SplitN(val, "''", 2); len(parts) == 2 {
			if decoded, err := url.PathUnescape(parts[1]); err == nil {
				return decoded
			}
		}
	}

	if idx := strings.Index(cd, "filename="); idx != -1 {
		val := cd[idx+len("filename="):]
		if i := strings.IndexByte(val, ';'); i != -1 {
			val = val[:i]
		}
		val = strings.TrimSpace(val)
		val = strings.Trim(val, `"`)
		if decoded, err := url.PathUnescape(val); err == nil {
			// Percent-decoded bytes may be UTF-8 or a legacy 8-bit encoding.
			return toUTF8(decoded, charmap.Windows1251, charmap.CodePage866)
		}
		return val
	}

	return ""
}

// decodeFilename ensures a ZIP entry name is valid UTF-8.
// ZIP archives without the UTF-8 flag (NonUTF8=true) store names in the local
// OEM code page — CP866 for Russian Windows/DOS tools.
func decodeFilename(s string) string {
	return toUTF8(s, charmap.CodePage866, charmap.Windows1251)
}

// toUTF8 returns s if it is already valid UTF-8. Otherwise it tries each
// candidate encoding and returns the decode with the most Cyrillic characters.
// Falls back to the original string if no candidate improves it.
func toUTF8(s string, candidates ...*charmap.Charmap) string {
	if utf8.ValidString(s) {
		return s
	}

	best := s
	bestScore := 0

	for _, enc := range candidates {
		decoded, err := enc.NewDecoder().String(s)
		if err != nil {
			continue
		}
		if score := cyrillicCount(decoded); score > bestScore {
			bestScore = score
			best = decoded
		}
	}

	return best
}

// cyrillicCount counts the number of Cyrillic characters (U+0400–U+04FF) in s.
func cyrillicCount(s string) int {
	n := 0
	for _, r := range s {
		if r >= 0x0400 && r <= 0x04FF {
			n++
		}
	}
	return n
}

// fallbackFilename builds a filename when the zip entry name is empty.
func fallbackFilename(author, title, format string) string {
	name := strings.TrimSpace(author + ". " + title)
	if name == ". " {
		name = "book"
	}
	return name + "." + format
}
