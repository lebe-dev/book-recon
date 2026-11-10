package rutracker

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

const topicsFeed = `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed">
  <channel>
    <item>
      <title>Лукьяненко - Ночной дозор (fb2)</title>
      <guid>https://rutracker.org/forum/viewtopic.php?t=6543210</guid>
      <comments>https://rutracker.org/forum/viewtopic.php?t=6543210</comments>
      <size>1048576</size>
      <link>JACKETT/dl/rutracker/?jackett_apikey=key&amp;path=abc&amp;file=1</link>
      <torznab:attr name="seeders" value="7"/>
      <torznab:attr name="peers" value="9"/>
    </item>
    <item>
      <title>Без сидов</title>
      <comments>https://rutracker.org/forum/viewtopic.php?t=111</comments>
      <link>JACKETT/dl/rutracker/?path=dead</link>
      <torznab:attr name="seeders" value="0"/>
    </item>
    <item>
      <title>Без номера темы</title>
      <comments>https://example.org/whatever</comments>
      <link>JACKETT/dl/rutracker/?path=nameless</link>
      <torznab:attr name="seeders" value="3"/>
    </item>
  </channel>
</rss>`

type jackettStub struct {
	srv     *httptest.Server
	queries []string
}

func newJackettStub(t *testing.T) *jackettStub {
	t.Helper()
	stub := &jackettStub{}
	stub.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasSuffix(r.URL.Path, "/torznab/api"):
			stub.queries = append(stub.queries, r.URL.RawQuery)
			_, _ = io.WriteString(w, strings.ReplaceAll(topicsFeed, "JACKETT", stub.srv.URL))
		case strings.HasPrefix(r.URL.Path, "/dl/rutracker/") && r.URL.Query().Get("path") == "abc":
			_, _ = w.Write([]byte("d8:announce0:e"))
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(stub.srv.Close)
	return stub
}

func (s *jackettStub) topics() *Topics {
	return NewTopics(TopicsConfig{
		JackettURL:      s.srv.URL,
		JackettAPIKey:   "key",
		JackettIndexer:  "rutracker",
		BookCategories:  []string{"7000"},
		AudioCategories: []string{"3030"},
	}, log.New(io.Discard))
}

func TestTopicsSearchKeepsSeededTopicsWithIDs(t *testing.T) {
	stub := newJackettStub(t)
	got, err := stub.topics().Search(context.Background(), "дозор", domain.TrackerKindBook, 20)
	if err != nil {
		t.Fatalf("Search() error: %v", err)
	}
	want := []domain.TrackerTopic{{ID: "6543210", Title: "Лукьяненко - Ночной дозор (fb2)", Size: 1048576, Seeders: 7, Peers: 9}}
	if len(got) != 1 || got[0] != want[0] {
		t.Fatalf("Search() = %+v, want %+v", got, want)
	}
}

func TestTopicsSearchUsesCategoriesOfTheKind(t *testing.T) {
	stub := newJackettStub(t)
	topics := stub.topics()
	_, _ = topics.Search(context.Background(), "дозор", domain.TrackerKindBook, 20)
	_, _ = topics.Search(context.Background(), "дозор", domain.TrackerKindAudio, 5)
	if len(stub.queries) != 2 {
		t.Fatalf("queries = %v", stub.queries)
	}
	if !strings.Contains(stub.queries[0], "cat=7000") || !strings.Contains(stub.queries[1], "cat=3030") {
		t.Fatalf("categories not applied: %v", stub.queries)
	}
	if !strings.Contains(stub.queries[1], "limit=5") {
		t.Fatalf("limit not applied: %s", stub.queries[1])
	}
}

func TestTopicsFetchTorrentOfAFoundTopic(t *testing.T) {
	stub := newJackettStub(t)
	topics := stub.topics()
	if _, err := topics.Search(context.Background(), "дозор", domain.TrackerKindBook, 20); err != nil {
		t.Fatalf("Search() error: %v", err)
	}
	data, err := topics.FetchTorrent(context.Background(), "6543210")
	if err != nil {
		t.Fatalf("FetchTorrent() error: %v", err)
	}
	if string(data) != "d8:announce0:e" {
		t.Fatalf("FetchTorrent() = %q", data)
	}
}

func TestTopicsFetchTorrentOfAnUnknownTopic(t *testing.T) {
	stub := newJackettStub(t)
	_, err := stub.topics().FetchTorrent(context.Background(), "6543210")
	var de *domain.DomainError
	if !errors.As(err, &de) || de.Code != domain.ErrCodeNotFound {
		t.Fatalf("FetchTorrent() error = %v, want NOT_FOUND", err)
	}
}

func TestTopicIDFrom(t *testing.T) {
	tests := map[string]string{
		"https://rutracker.org/forum/viewtopic.php?t=6543210": "6543210",
		"https://rutracker.org/forum/viewtopic.php?t=12&x=1":  "12",
		"https://rutracker.org/forum/viewtopic.php?t=abc":     "",
		"https://example.org/":                                "",
		"":                                                    "",
	}
	for in, want := range tests {
		if got := topicIDFrom(in); got != want {
			t.Errorf("topicIDFrom(%q) = %q, want %q", in, got, want)
		}
	}
}
