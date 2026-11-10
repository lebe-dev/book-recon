package rutracker

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

const (
	// maxTopicTorrentSize caps a .torrent fetched for the app.
	maxTopicTorrentSize = 10 << 20
	// topicLinkTTL keeps download links long enough for a retry hours after the search.
	topicLinkTTL = 24 * time.Hour
	// maxTopicLinks bounds the memory held by remembered download links.
	maxTopicLinks = 10_000
)

// TopicsConfig holds Jackett settings for searches made by the Android app.
type TopicsConfig struct {
	JackettURL      string
	JackettAPIKey   string
	JackettIndexer  string
	BookCategories  []string
	AudioCategories []string
	Timeout         time.Duration
}

// Topics implements domain.TopicTracker over Jackett: the RuTracker account lives in Jackett,
// so the app searches and fetches .torrent files without credentials of its own. Jackett's
// download links are opaque, so the link of every found topic is remembered by topic ID.
type Topics struct {
	httpClient *http.Client
	config     TopicsConfig
	logger     *log.Logger
	now        func() time.Time

	mu    sync.Mutex
	links map[string]topicLink
}

type topicLink struct {
	url   string
	added time.Time
}

func NewTopics(cfg TopicsConfig, logger *log.Logger) *Topics {
	timeout := cfg.Timeout
	if timeout <= 0 {
		timeout = defaultJackettTimeout
	}
	return &Topics{
		httpClient: &http.Client{Timeout: timeout},
		config:     cfg,
		logger:     logger,
		now:        time.Now,
		links:      make(map[string]topicLink),
	}
}

// Search queries Jackett in the categories of kind and keeps topics with seeders.
func (t *Topics) Search(ctx context.Context, query string, kind domain.TrackerKind, limit int) ([]domain.TrackerTopic, error) {
	categories := t.config.BookCategories
	if kind == domain.TrackerKindAudio {
		categories = t.config.AudioCategories
	}
	u := fmt.Sprintf("%s/api/v2.0/indexers/%s/results/torznab/api?apikey=%s&t=search&q=%s&limit=%d",
		strings.TrimRight(t.config.JackettURL, "/"),
		url.PathEscape(t.config.JackettIndexer),
		url.QueryEscape(t.config.JackettAPIKey),
		url.QueryEscape(query),
		limit,
	)
	if len(categories) > 0 {
		u += "&cat=" + url.QueryEscape(strings.Join(categories, ","))
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeServiceDown, "failed to create search request", err)
	}
	resp, err := t.httpClient.Do(req)
	if err != nil {
		t.logger.Error("jackett search for the app failed", "error", err)
		return nil, domain.WrapError(domain.ErrCodeServiceDown, "jackett unavailable", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return nil, domain.NewError(domain.ErrCodeServiceDown, fmt.Sprintf("jackett returned status %d", resp.StatusCode))
	}

	items, err := ParseTorznab(resp.Body)
	if err != nil {
		if te, ok := err.(*TorznabError); ok {
			t.logger.Error("jackett returned an error", "code", te.Code, "description", te.Description)
			return nil, domain.WrapError(domain.ErrCodeServiceDown, te.Error(), err)
		}
		return nil, domain.WrapError(domain.ErrCodeProviderError, "failed to parse jackett response", err)
	}

	topics := make([]domain.TrackerTopic, 0, len(items))
	links := make(map[string]string, len(items))
	for _, item := range items {
		id := topicIDFrom(item.Comments)
		if id == "" {
			id = topicIDFrom(item.GUID)
		}
		if item.Seeders <= 0 || item.Link == "" || id == "" {
			continue
		}
		links[id] = item.Link
		topics = append(topics, domain.TrackerTopic{
			ID: id, Title: item.Title, Size: item.Size, Seeders: item.Seeders, Peers: item.Peers,
		})
	}
	t.remember(links)

	t.logger.Info("rutracker search for the app done", "kind", kind, "results", len(topics))
	return topics, nil
}

// FetchTorrent downloads the .torrent of a topic found by an earlier Search.
func (t *Topics) FetchTorrent(ctx context.Context, topicID string) ([]byte, error) {
	link, ok := t.link(topicID)
	if !ok {
		return nil, domain.NewError(domain.ErrCodeNotFound, "topic was not found by a recent search")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeProviderError, "failed to create torrent request", err)
	}
	resp, err := t.httpClient.Do(req)
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeServiceDown, "failed to download .torrent", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return nil, domain.NewError(domain.ErrCodeServiceDown, fmt.Sprintf("download .torrent: HTTP %d", resp.StatusCode))
	}
	data, err := io.ReadAll(io.LimitReader(resp.Body, maxTopicTorrentSize+1))
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeServiceDown, "failed to read .torrent", err)
	}
	if len(data) > maxTopicTorrentSize {
		return nil, domain.NewError(domain.ErrCodeTorrentTooLarge, ".torrent file is too large")
	}
	return data, nil
}

func (t *Topics) remember(links map[string]string) {
	t.mu.Lock()
	defer t.mu.Unlock()
	now := t.now()
	for id, link := range links {
		t.links[id] = topicLink{url: link, added: now}
	}
	if len(t.links) <= maxTopicLinks {
		return
	}
	for id, l := range t.links {
		if now.Sub(l.added) > topicLinkTTL {
			delete(t.links, id)
		}
	}
	// Still too many: forget everything older than this search.
	if len(t.links) > maxTopicLinks {
		for id, l := range t.links {
			if l.added.Before(now) {
				delete(t.links, id)
			}
		}
	}
}

func (t *Topics) link(topicID string) (string, bool) {
	t.mu.Lock()
	defer t.mu.Unlock()
	l, ok := t.links[topicID]
	if !ok || t.now().Sub(l.added) > topicLinkTTL {
		return "", false
	}
	return l.url, true
}

// topicIDFrom extracts the topic number from a viewtopic.php?t=<id> URL.
func topicIDFrom(raw string) string {
	u, err := url.Parse(raw)
	if err != nil || !strings.HasSuffix(u.Path, "/viewtopic.php") {
		return ""
	}
	id := u.Query().Get("t")
	if _, err := strconv.ParseUint(id, 10, 64); err != nil {
		return ""
	}
	return id
}
