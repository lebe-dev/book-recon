package flibusta

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/charmbracelet/log"
)

func TestCheckHealth_OK(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodHead {
			t.Errorf("expected HEAD, got %s", r.Method)
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())
	statuses := p.CheckHealth(context.Background())

	if len(statuses) != 1 {
		t.Fatalf("expected 1 status, got %d", len(statuses))
	}
	if !statuses[0].Healthy {
		t.Errorf("expected healthy, got unhealthy: %s", statuses[0].Detail)
	}
	if statuses[0].Name != providerName {
		t.Errorf("name = %q, want %q", statuses[0].Name, providerName)
	}
}

func TestCheckHealth_ServerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())
	statuses := p.CheckHealth(context.Background())

	if len(statuses) != 1 {
		t.Fatalf("expected 1 status, got %d", len(statuses))
	}
	if statuses[0].Healthy {
		t.Error("expected unhealthy for 500 response")
	}
}

func TestCheckHealth_ConnectionRefused(t *testing.T) {
	p := newWithBaseURL("http://127.0.0.1:1", http.DefaultClient, log.Default())
	statuses := p.CheckHealth(context.Background())

	if len(statuses) != 1 {
		t.Fatalf("expected 1 status, got %d", len(statuses))
	}
	if statuses[0].Healthy {
		t.Error("expected unhealthy for connection error")
	}
}
