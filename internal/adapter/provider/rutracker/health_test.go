package rutracker

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/charmbracelet/log"
)

func TestCheckHealth_AllOK(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		switch q.Get("t") {
		case "caps":
			w.WriteHeader(http.StatusOK)
		case "search":
			w.Header().Set("Content-Type", "application/xml")
			_, _ = w.Write([]byte(`<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel></channel></rss>`))
		default:
			w.WriteHeader(http.StatusBadRequest)
		}
	}))
	defer srv.Close()

	p := New(Config{
		JackettURL:     srv.URL,
		JackettAPIKey:  "test-key",
		JackettIndexer: "rutracker",
	}, nil, log.Default())
	p.httpClient = srv.Client()

	statuses := p.CheckHealth(context.Background())
	if len(statuses) != 2 {
		t.Fatalf("expected 2 statuses, got %d", len(statuses))
	}

	// Jackett status
	if statuses[0].Name != "Jackett" {
		t.Errorf("statuses[0].Name = %q", statuses[0].Name)
	}
	if !statuses[0].Healthy {
		t.Errorf("Jackett should be healthy, got: %s", statuses[0].Detail)
	}

	// RuTracker status
	if statuses[1].Name != "RuTracker" {
		t.Errorf("statuses[1].Name = %q", statuses[1].Name)
	}
	if !statuses[1].Healthy {
		t.Errorf("RuTracker should be healthy, got: %s", statuses[1].Detail)
	}
}

func TestCheckHealth_JackettDown(t *testing.T) {
	p := New(Config{
		JackettURL:     "http://127.0.0.1:1",
		JackettAPIKey:  "test-key",
		JackettIndexer: "rutracker",
	}, nil, log.Default())

	statuses := p.CheckHealth(context.Background())
	if len(statuses) != 2 {
		t.Fatalf("expected 2 statuses, got %d", len(statuses))
	}

	if statuses[0].Healthy {
		t.Error("Jackett should be unhealthy")
	}
	if statuses[1].Healthy {
		t.Error("RuTracker should be unhealthy when Jackett is down")
	}
	if !strings.Contains(statuses[1].Detail, "skipped") {
		t.Errorf("RuTracker detail should mention skipped, got: %s", statuses[1].Detail)
	}
}

func TestCheckHealth_JackettOK_RuTrackerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		q := r.URL.Query()
		switch q.Get("t") {
		case "caps":
			w.WriteHeader(http.StatusOK)
		case "search":
			w.Header().Set("Content-Type", "application/xml")
			_, _ = w.Write([]byte(`<?xml version="1.0" encoding="UTF-8"?>
<error code="300" description="Indexer unavailable" />`))
		}
	}))
	defer srv.Close()

	p := New(Config{
		JackettURL:     srv.URL,
		JackettAPIKey:  "test-key",
		JackettIndexer: "rutracker",
	}, nil, log.Default())
	p.httpClient = srv.Client()

	statuses := p.CheckHealth(context.Background())
	if len(statuses) != 2 {
		t.Fatalf("expected 2 statuses, got %d", len(statuses))
	}

	if !statuses[0].Healthy {
		t.Error("Jackett should be healthy")
	}
	if statuses[1].Healthy {
		t.Error("RuTracker should be unhealthy")
	}
	if !strings.Contains(statuses[1].Detail, "Indexer unavailable") {
		t.Errorf("RuTracker detail = %q, want to contain 'Indexer unavailable'", statuses[1].Detail)
	}
}
