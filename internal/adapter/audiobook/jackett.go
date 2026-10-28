package audiobook

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/adapter/provider/rutracker"
	"github.com/lebe-dev/book-recon/internal/domain"
)

// maxTorrentFileSize caps the .torrent metadata file fetched from Jackett.
const maxTorrentFileSize = 10 << 20

// JackettConfig holds Jackett connection settings for audiobook search.
type JackettConfig struct {
	URL        string
	APIKey     string
	Indexer    string
	Categories []string
	Timeout    time.Duration
}

// Jackett searches audiobooks via Jackett Torznab API.
type Jackett struct {
	httpClient *http.Client
	config     JackettConfig
	logger     *log.Logger
}

func NewJackett(cfg JackettConfig, logger *log.Logger) *Jackett {
	timeout := cfg.Timeout
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	return &Jackett{
		httpClient: &http.Client{Timeout: timeout},
		config:     cfg,
		logger:     logger,
	}
}

// Search queries Jackett restricted to the configured audiobook categories.
// Results without seeders are dropped: they can't be downloaded anyway.
func (j *Jackett) Search(ctx context.Context, query string, limit int) ([]domain.Audiobook, error) {
	u := fmt.Sprintf("%s/api/v2.0/indexers/%s/results/torznab/api?apikey=%s&t=search&q=%s&limit=%d",
		strings.TrimRight(j.config.URL, "/"),
		url.PathEscape(j.config.Indexer),
		url.QueryEscape(j.config.APIKey),
		url.QueryEscape(query),
		limit,
	)
	if len(j.config.Categories) > 0 {
		u += "&cat=" + url.QueryEscape(strings.Join(j.config.Categories, ","))
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeServiceDown, "failed to create search request", err)
	}

	resp, err := j.httpClient.Do(req)
	if err != nil {
		j.logger.Error("jackett audiobook search failed", "error", err)
		return nil, domain.WrapError(domain.ErrCodeServiceDown, "jackett unavailable", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return nil, domain.NewError(domain.ErrCodeServiceDown, fmt.Sprintf("jackett returned status %d", resp.StatusCode))
	}

	items, err := rutracker.ParseTorznab(resp.Body)
	if err != nil {
		if te, ok := err.(*rutracker.TorznabError); ok {
			j.logger.Error("jackett returned error", "code", te.Code, "description", te.Description)
			return nil, domain.WrapError(domain.ErrCodeServiceDown, te.Error(), err)
		}
		return nil, domain.WrapError(domain.ErrCodeProviderError, "failed to parse jackett response", err)
	}

	results := make([]domain.Audiobook, 0, len(items))
	for _, item := range items {
		if item.Seeders <= 0 || item.Link == "" {
			continue
		}
		results = append(results, domain.NewAudiobook(domain.Audiobook{
			Title:      item.Title,
			Size:       item.Size,
			Seeders:    item.Seeders,
			Peers:      item.Peers,
			TorrentURL: item.Link,
		}))
	}

	j.logger.Info("audiobook search done", "query", query, "results", len(results))
	return results, nil
}

// FetchTorrent downloads the .torrent file. Magnet links are not supported:
// we need the file list before any peer connection to let the user choose.
func (j *Jackett) FetchTorrent(ctx context.Context, torrentURL string) ([]byte, error) {
	if strings.HasPrefix(torrentURL, "magnet:") {
		return nil, domain.NewError(domain.ErrCodeProviderError, "magnet links are not supported")
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, torrentURL, nil)
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeProviderError, "failed to create torrent request", err)
	}

	resp, err := j.httpClient.Do(req)
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeServiceDown, "failed to download .torrent", err)
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		return nil, domain.NewError(domain.ErrCodeServiceDown, fmt.Sprintf("download .torrent: HTTP %d", resp.StatusCode))
	}

	data, err := io.ReadAll(io.LimitReader(resp.Body, maxTorrentFileSize+1))
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeServiceDown, "failed to read .torrent", err)
	}
	if len(data) > maxTorrentFileSize {
		return nil, domain.NewError(domain.ErrCodeProviderError, ".torrent file is too large")
	}
	return data, nil
}
