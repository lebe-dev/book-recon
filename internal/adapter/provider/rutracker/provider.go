package rutracker

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

// Config holds RuTracker provider configuration.
type Config struct {
	JackettURL        string
	JackettAPIKey     string
	JackettIndexer    string
	JackettCategories []string
	DownloadTimeout   time.Duration
	MaxBooks          int
	MaxTorrentSize    int64
	DownloadDir       string
}

// Provider implements domain.BookProvider for RuTracker via Jackett.
type Provider struct {
	httpClient *http.Client
	torrentMgr *TorrentManager
	config     Config
	logger     *log.Logger
}

// New creates a new RuTracker provider.
func New(cfg Config, torrentMgr *TorrentManager, logger *log.Logger) *Provider {
	return &Provider{
		httpClient: &http.Client{Timeout: 30 * time.Second},
		torrentMgr: torrentMgr,
		config:     cfg,
		logger:     logger,
	}
}

func (p *Provider) Name() string { return "RuTracker" }

// Search queries Jackett Torznab API for books.
func (p *Provider) Search(ctx context.Context, query string, limit int) ([]domain.SearchResult, error) {
	u := fmt.Sprintf("%s/api/v2.0/indexers/%s/results/torznab/api?apikey=%s&t=book&q=%s&limit=%d",
		strings.TrimRight(p.config.JackettURL, "/"),
		url.PathEscape(p.config.JackettIndexer),
		url.QueryEscape(p.config.JackettAPIKey),
		url.QueryEscape(query),
		limit,
	)

	if len(p.config.JackettCategories) > 0 {
		u += "&cat=" + url.QueryEscape(strings.Join(p.config.JackettCategories, ","))
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeServiceDown, "failed to create search request", err)
	}

	resp, err := p.httpClient.Do(req)
	if err != nil {
		p.logger.Error("jackett search failed", "error", err)
		return nil, domain.WrapError(domain.ErrCodeServiceDown, "jackett unavailable", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return nil, domain.NewError(domain.ErrCodeServiceDown, fmt.Sprintf("jackett returned status %d", resp.StatusCode))
	}

	items, err := ParseTorznab(resp.Body)
	if err != nil {
		if te, ok := err.(*TorznabError); ok {
			p.logger.Error("jackett returned error", "code", te.Code, "description", te.Description)
			return nil, domain.WrapError(domain.ErrCodeServiceDown, te.Error(), err)
		}
		return nil, domain.WrapError(domain.ErrCodeProviderError, "failed to parse jackett response", err)
	}

	var results []domain.SearchResult
	for _, item := range items {
		// Filter out items with 0 seeders.
		if item.Seeders <= 0 {
			continue
		}

		formats := DetectFormats(item.Title)
		if len(formats) == 0 {
			// Default: assume common formats are present.
			formats = []string{"fb2", "epub", "pdf"}
		}

		domainFormats := make([]domain.Format, 0, len(formats))
		for _, f := range formats {
			domainFormats = append(domainFormats, domain.Format(f))
		}

		// Parse title: usually "Author - Title (format)" or similar.
		title, author := parseRutrackerTitle(item.Title)

		book := domain.Book{
			Title:     title,
			Author:    author,
			Formats:   domainFormats,
			Provider:  "RuTracker",
			SourceURL: item.Link,
			Metadata: map[string]string{
				"seeds":        strconv.Itoa(item.Seeders),
				"peers":        strconv.Itoa(item.Peers),
				"torrent_size": strconv.FormatInt(item.Size, 10),
			},
		}

		results = append(results, domain.NewSearchResult(book))
	}

	p.logger.Info("rutracker search done", "query", query, "results", len(results))
	return results, nil
}

// Download fetches a book from a torrent.
// This is a synchronous, potentially long-running operation.
func (p *Provider) Download(ctx context.Context, result domain.SearchResult, format domain.Format) (io.ReadCloser, string, error) {
	torrentURL := result.Book.SourceURL
	if torrentURL == "" {
		return nil, "", domain.NewError(domain.ErrCodeProviderError, "no torrent URL")
	}

	// Check seeders.
	if seeds, ok := result.Book.Metadata["seeds"]; ok {
		if s, _ := strconv.Atoi(seeds); s <= 0 {
			return nil, "", domain.NewError(domain.ErrCodeNoSeeders, "no seeders available")
		}
	}

	// Check torrent size limit.
	if sizeStr, ok := result.Book.Metadata["torrent_size"]; ok {
		if size, _ := strconv.ParseInt(sizeStr, 10, 64); size > 0 && p.config.MaxTorrentSize > 0 && size > p.config.MaxTorrentSize {
			return nil, "", domain.NewError(domain.ErrCodeTorrentTooLarge,
				fmt.Sprintf("torrent size %d exceeds limit %d", size, p.config.MaxTorrentSize))
		}
	}

	// Download .torrent file from Jackett.
	p.logger.Debug("downloading .torrent", "url", torrentURL)
	torrentData, err := p.downloadTorrentFile(ctx, torrentURL)
	if err != nil {
		return nil, "", domain.WrapError(domain.ErrCodeProviderError, "failed to download .torrent", err)
	}

	// Download torrent content.
	dlCtx, cancel := context.WithTimeout(ctx, p.config.DownloadTimeout)
	defer cancel()

	files, infoHash, err := p.torrentMgr.Download(dlCtx, torrentData)
	if err != nil {
		if dlCtx.Err() != nil {
			return nil, "", domain.NewError(domain.ErrCodeTimeout,
				fmt.Sprintf("torrent download timed out after %s", p.config.DownloadTimeout))
		}
		// Check if it's a size limit error.
		if strings.Contains(err.Error(), "too large") {
			return nil, "", domain.NewError(domain.ErrCodeTorrentTooLarge, err.Error())
		}
		return nil, "", domain.WrapError(domain.ErrCodeProviderError, "torrent download failed", err)
	}

	// Pick files by format.
	picked := PickFiles(files, format, p.config.MaxBooks)
	if len(picked) == 0 {
		_ = p.torrentMgr.Cleanup(infoHash)
		return nil, "", domain.NewError(domain.ErrCodeFormatNA,
			fmt.Sprintf("no files in format %s found in torrent", format))
	}

	// Return first file. For multi-file, use DownloadMulti.
	first := picked[0]
	f, err := os.Open(first.Path)
	if err != nil {
		_ = p.torrentMgr.Cleanup(infoHash)
		return nil, "", domain.WrapError(domain.ErrCodeProviderError, "failed to open downloaded file", err)
	}

	// Wrap reader to cleanup on close.
	reader := &cleanupReader{
		ReadCloser: f,
		cleanup: func() {
			_ = p.torrentMgr.Cleanup(infoHash)
		},
	}

	return reader, first.Name, nil
}

// DownloadMulti downloads all matching files from a torrent.
// Returns picked files and a cleanup function that MUST be called after use.
func (p *Provider) DownloadMulti(ctx context.Context, result domain.SearchResult, format domain.Format) ([]PickedFile, func(), error) {
	torrentURL := result.Book.SourceURL
	if torrentURL == "" {
		return nil, nil, domain.NewError(domain.ErrCodeProviderError, "no torrent URL")
	}

	if seeds, ok := result.Book.Metadata["seeds"]; ok {
		if s, _ := strconv.Atoi(seeds); s <= 0 {
			return nil, nil, domain.NewError(domain.ErrCodeNoSeeders, "no seeders available")
		}
	}

	if sizeStr, ok := result.Book.Metadata["torrent_size"]; ok {
		if size, _ := strconv.ParseInt(sizeStr, 10, 64); size > 0 && p.config.MaxTorrentSize > 0 && size > p.config.MaxTorrentSize {
			return nil, nil, domain.NewError(domain.ErrCodeTorrentTooLarge,
				fmt.Sprintf("torrent size %d exceeds limit %d", size, p.config.MaxTorrentSize))
		}
	}

	torrentData, err := p.downloadTorrentFile(ctx, torrentURL)
	if err != nil {
		return nil, nil, domain.WrapError(domain.ErrCodeProviderError, "failed to download .torrent", err)
	}

	dlCtx, cancel := context.WithTimeout(ctx, p.config.DownloadTimeout)
	defer cancel()

	files, infoHash, err := p.torrentMgr.Download(dlCtx, torrentData)
	if err != nil {
		if dlCtx.Err() != nil {
			return nil, nil, domain.NewError(domain.ErrCodeTimeout,
				fmt.Sprintf("torrent download timed out after %s", p.config.DownloadTimeout))
		}
		if strings.Contains(err.Error(), "too large") {
			return nil, nil, domain.NewError(domain.ErrCodeTorrentTooLarge, err.Error())
		}
		return nil, nil, domain.WrapError(domain.ErrCodeProviderError, "torrent download failed", err)
	}

	picked := PickFiles(files, format, p.config.MaxBooks)
	if len(picked) == 0 {
		_ = p.torrentMgr.Cleanup(infoHash)
		return nil, nil, domain.NewError(domain.ErrCodeFormatNA,
			fmt.Sprintf("no files in format %s found in torrent", format))
	}

	cleanup := func() {
		_ = p.torrentMgr.Cleanup(infoHash)
	}

	return picked, cleanup, nil
}

// Close shuts down the torrent manager.
func (p *Provider) Close() {
	p.torrentMgr.Close()
}

func (p *Provider) downloadTorrentFile(ctx context.Context, torrentURL string) ([]byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, torrentURL, nil)
	if err != nil {
		return nil, err
	}

	resp, err := p.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("download .torrent: HTTP %d", resp.StatusCode)
	}

	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read .torrent body: %w", err)
	}

	return data, nil
}

// parseRutrackerTitle attempts to split "Author - Title (format)" into title and author.
func parseRutrackerTitle(raw string) (title, author string) {
	// Remove trailing format hints like "(fb2)", "(fb2, epub)" etc.
	cleaned := raw
	if idx := strings.LastIndex(cleaned, "("); idx > 0 {
		inner := strings.ToLower(cleaned[idx:])
		knownFormats := []string{"fb2", "epub", "mobi", "pdf", "djvu"}
		isFormatHint := false
		for _, f := range knownFormats {
			if strings.Contains(inner, f) {
				isFormatHint = true
				break
			}
		}
		if isFormatHint {
			cleaned = strings.TrimSpace(cleaned[:idx])
		}
	}

	// Try "Author - Title" split.
	if parts := strings.SplitN(cleaned, " - ", 2); len(parts) == 2 {
		author = strings.TrimSpace(parts[0])
		title = strings.TrimSpace(parts[1])
		return
	}

	// Try "Author — Title" (em dash).
	if parts := strings.SplitN(cleaned, " — ", 2); len(parts) == 2 {
		author = strings.TrimSpace(parts[0])
		title = strings.TrimSpace(parts[1])
		return
	}

	return strings.TrimSpace(cleaned), ""
}

// cleanupReader wraps a ReadCloser and runs cleanup after close.
type cleanupReader struct {
	io.ReadCloser
	cleanup func()
	once    sync.Once
}

func (r *cleanupReader) Close() error {
	err := r.ReadCloser.Close()
	r.once.Do(r.cleanup)
	return err
}
