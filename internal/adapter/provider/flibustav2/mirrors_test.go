package flibustav2

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/charmbracelet/log"
)

func newMirrorDomainProvider(urls ...string) *DomainProvider {
	return NewDomainProvider(urls, log.Default())
}

func okFeedServer(t *testing.T) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		_, _ = io.WriteString(w, opdsFeedXML(opdsEntryXML(1, "Книга", "Автор", []Format{FormatFB2}), ""))
	}))
}

func TestDomainSearch_FallsBackWhenMirrorIsDown(t *testing.T) {
	down := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusBadGateway)
	}))
	defer down.Close()
	up := okFeedServer(t)
	defer up.Close()

	p := newMirrorDomainProvider(down.URL, up.URL)

	results, err := p.Search(context.Background(), "q", 5)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(results) != 1 {
		t.Fatalf("expected 1 result, got %d", len(results))
	}
}

func TestDomainSearch_FallsBackWhenConnectionRefused(t *testing.T) {
	up := okFeedServer(t)
	defer up.Close()

	p := newMirrorDomainProvider("http://127.0.0.1:1", up.URL)

	if _, err := p.Search(context.Background(), "q", 5); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
}

func TestDomainSearch_DoesNotFallBackOnClientError(t *testing.T) {
	calls := 0
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer bad.Close()
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { calls++ }))
	defer other.Close()

	p := newMirrorDomainProvider(bad.URL, other.URL)

	if _, err := p.Search(context.Background(), "q", 5); err == nil {
		t.Fatal("expected an error")
	}
	if calls != 0 {
		t.Errorf("second mirror was called %d times, want 0", calls)
	}
}

func TestDomainSearch_RemembersWorkingMirror(t *testing.T) {
	downCalls := 0
	down := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		downCalls++
		w.WriteHeader(http.StatusServiceUnavailable)
	}))
	defer down.Close()
	up := okFeedServer(t)
	defer up.Close()

	p := newMirrorDomainProvider(down.URL, up.URL)

	for range 2 {
		if _, err := p.Search(context.Background(), "q", 5); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	}
	if downCalls != 1 {
		t.Errorf("broken mirror was called %d times, want 1", downCalls)
	}
}

func TestDomainCheckHealth_HealthyWhenAnyMirrorAnswers(t *testing.T) {
	up := okFeedServer(t)
	defer up.Close()

	p := newMirrorDomainProvider("http://127.0.0.1:1", up.URL)

	statuses := p.CheckHealth(context.Background())
	if len(statuses) != 1 || !statuses[0].Healthy {
		t.Fatalf("expected one healthy status, got %+v", statuses)
	}
}
