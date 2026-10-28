package audiobook

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

const torznabFixture = `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed">
<channel>
<item>
  <title>Толстой Лев - Война и мир [Иванов, 128 kbps, MP3]</title>
  <size>1073741824</size>
  <link>http://jackett/dl/1</link>
  <torznab:attr name="seeders" value="12"/>
  <torznab:attr name="peers" value="3"/>
</item>
<item>
  <title>Dead torrent</title>
  <size>100</size>
  <link>http://jackett/dl/2</link>
  <torznab:attr name="seeders" value="0"/>
</item>
</channel>
</rss>`

func TestJackettSearch(t *testing.T) {
	var gotQuery string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotQuery = r.URL.RawQuery
		_, _ = io.WriteString(w, torznabFixture)
	}))
	defer srv.Close()

	j := NewJackett(JackettConfig{
		URL:        srv.URL,
		APIKey:     "key",
		Indexer:    "rutracker",
		Categories: []string{"3030", "100500"},
	}, log.New(io.Discard))

	results, err := j.Search(context.Background(), "война и мир", 20)
	if err != nil {
		t.Fatalf("Search: %v", err)
	}
	if !strings.Contains(gotQuery, "cat=3030%2C100500") || !strings.Contains(gotQuery, "t=search") {
		t.Errorf("query = %s", gotQuery)
	}
	if len(results) != 1 {
		t.Fatalf("results = %d, want 1 (zero-seed item filtered)", len(results))
	}
	r := results[0]
	if r.Seeders != 12 || r.Size != 1<<30 || r.TorrentURL != "http://jackett/dl/1" || len(r.ID) != 12 {
		t.Errorf("unexpected result: %+v", r)
	}
}

func TestJackettSearchError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, `<error code="100" description="Invalid API Key"/>`)
	}))
	defer srv.Close()

	j := NewJackett(JackettConfig{URL: srv.URL, Indexer: "rutracker"}, log.New(io.Discard))
	_, err := j.Search(context.Background(), "x", 10)
	if code, _ := domain.ErrorCodeFrom(err); code != domain.ErrCodeServiceDown {
		t.Errorf("err = %v, want SERVICE_DOWN", err)
	}
}

func TestFetchTorrentRejectsMagnet(t *testing.T) {
	j := NewJackett(JackettConfig{}, log.New(io.Discard))
	if _, err := j.FetchTorrent(context.Background(), "magnet:?xt=urn:btih:abc"); err == nil {
		t.Fatal("expected error for magnet link")
	}
}
