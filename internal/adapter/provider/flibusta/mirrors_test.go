package flibusta

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"testing"

	"github.com/charmbracelet/log"
)

const mirrorTestPage = `<html><body><h3>Найденные книги</h3><ul>
<li><a href="/b/1">Книга</a> - <a href="/a/2">Автор</a> <a href="/b/1/fb2">(fb2)</a></li>
</ul></body></html>`

func newMirrorProvider(client *http.Client, mirrors ...string) *Provider {
	return &Provider{client: client, mirrors: mirrors, logger: log.Default()}
}

func TestSearch_FallsBackWhenFirstMirrorIsDown(t *testing.T) {
	down := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
	}))
	defer down.Close()
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, mirrorTestPage)
	}))
	defer up.Close()

	p := newMirrorProvider(http.DefaultClient, down.URL, up.URL)

	results, err := p.Search(context.Background(), "q", 5)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(results) != 1 {
		t.Fatalf("expected 1 result, got %d", len(results))
	}
}

func TestSearch_FallsBackWhenFirstMirrorRefusesConnection(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, mirrorTestPage)
	}))
	defer up.Close()

	p := newMirrorProvider(http.DefaultClient, "http://127.0.0.1:1", up.URL)

	if _, err := p.Search(context.Background(), "q", 5); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestSearch_DoesNotFallBackOnNotFound(t *testing.T) {
	calls := 0
	notFound := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer notFound.Close()
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		calls++
	}))
	defer other.Close()

	p := newMirrorProvider(http.DefaultClient, notFound.URL, other.URL)

	if _, err := p.Search(context.Background(), "q", 5); err == nil {
		t.Fatal("expected an error")
	}
	if calls != 0 {
		t.Errorf("second mirror was called %d times, want 0", calls)
	}
}

func TestSearch_RemembersWorkingMirror(t *testing.T) {
	downCalls := 0
	down := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		downCalls++
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer down.Close()
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, mirrorTestPage)
	}))
	defer up.Close()

	p := newMirrorProvider(http.DefaultClient, down.URL, up.URL)

	for range 2 {
		if _, err := p.Search(context.Background(), "q", 5); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	}
	if downCalls != 1 {
		t.Errorf("broken mirror was called %d times, want 1", downCalls)
	}
}

func TestCheckHealth_HealthyWhenAnyMirrorAnswers(t *testing.T) {
	up := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer up.Close()

	p := newMirrorProvider(http.DefaultClient, "http://127.0.0.1:1", up.URL)

	statuses := p.CheckHealth(context.Background())
	if len(statuses) != 1 || !statuses[0].Healthy {
		t.Fatalf("expected one healthy status, got %+v", statuses)
	}
}

func TestMirrorList(t *testing.T) {
	known := []string{"https://flibusta.site", "https://flibusta.is"}

	tests := []struct {
		name string
		in   []string
		want []string
	}{
		{"nothing configured uses the known mirrors", nil, known},
		{"blanks are ignored", []string{"", "  "}, known},
		{"configured order is kept", []string{"https://flibusta.is", "https://example.org"}, []string{"https://flibusta.is", "https://example.org"}},
		{"slashes and duplicates are dropped", []string{"https://a.org/", " https://a.org ", "https://b.org"}, []string{"https://a.org", "https://b.org"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if got := mirrorList(tt.in); !slices.Equal(got, tt.want) {
				t.Errorf("mirrorList(%v) = %v, want %v", tt.in, got, tt.want)
			}
		})
	}
}
