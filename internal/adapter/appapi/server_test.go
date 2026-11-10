package appapi_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/charmbracelet/log"
	"github.com/coder/websocket"
	"github.com/lebe-dev/book-recon/internal/adapter/appapi"
	"github.com/lebe-dev/book-recon/internal/adapter/storage"
	"github.com/lebe-dev/book-recon/internal/domain"
	"github.com/lebe-dev/book-recon/internal/usecase"
)

type fixture struct {
	srv     *httptest.Server
	clients *usecase.AppClientService
	access  *storage.AccessRepo
	echo    string // address of the TCP echo server that stands in for rutracker.org:443
}

func newFixture(t *testing.T, mutate ...func(*appapi.Config)) *fixture {
	t.Helper()
	db, err := storage.NewDB(":memory:")
	if err != nil {
		t.Fatalf("open db: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	logger := log.New(io.Discard)
	accessRepo := storage.NewAccessRepo(db)
	access := usecase.NewAccessService(accessRepo, storage.NewUserRegistryRepo(db), nil, nil, logger)
	clients := usecase.NewAppClientService(storage.NewAppClientRepo(db), access, logger)

	echo := startEcho(t)
	cfg := appapi.Config{
		AllowedHosts: []string{"rutracker.org", "flibusta.is"},
		AllowedPorts: []int{443, 80},
		BotUsername:  func() string { return "book_recon_bot" },
		Version:      "1.2.3",
		Dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, network, echo)
		},
	}
	for _, m := range mutate {
		m(&cfg)
	}
	srv := httptest.NewServer(appapi.NewServer(cfg, clients, logger).Handler())
	t.Cleanup(srv.Close)
	return &fixture{srv: srv, clients: clients, access: accessRepo, echo: echo}
}

func startEcho(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	t.Cleanup(func() { _ = ln.Close() })
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer func() { _ = conn.Close() }()
				_, _ = io.Copy(conn, conn)
			}()
		}
	}()
	return ln.Addr().String()
}

func (f *fixture) do(t *testing.T, method, path, token string, body any) (*http.Response, map[string]any) {
	t.Helper()
	var reader io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		reader = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, f.srv.URL+path, reader)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("%s %s: %v", method, path, err)
	}
	defer func() { _ = resp.Body.Close() }()
	var out map[string]any
	_ = json.NewDecoder(resp.Body).Decode(&out)
	return resp, out
}

func (f *fixture) register(t *testing.T) string {
	t.Helper()
	resp, body := f.do(t, http.MethodPost, "/api/v1/clients", "", map[string]string{"deviceName": "Pixel"})
	if resp.StatusCode != http.StatusCreated {
		t.Fatalf("register status = %d", resp.StatusCode)
	}
	token, _ := body["token"].(string)
	if token == "" {
		t.Fatal("register returned no token")
	}
	return token
}

// approve links the client to a Telegram user and approves that user.
func (f *fixture) approve(t *testing.T, token string, telegramID int64) {
	t.Helper()
	ctx := context.Background()
	client, err := f.clients.Authenticate(ctx, token)
	if err != nil {
		t.Fatalf("authenticate: %v", err)
	}
	code, _, _ := f.clients.IssueLinkCode(ctx, client)
	if _, err := f.clients.LinkTelegram(ctx, code, usecase.TelegramUser{ID: telegramID, Username: "reader"}); err != nil {
		t.Fatalf("link: %v", err)
	}
	if err := f.access.SetStatus(ctx, telegramID, domain.AccessStatusApproved); err != nil {
		t.Fatalf("approve: %v", err)
	}
}

func (f *fixture) dialTunnel(t *testing.T, token, query string) (*websocket.Conn, *http.Response, error) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	header := http.Header{}
	if token != "" {
		header.Set("Authorization", "Bearer "+token)
	}
	url := "ws" + strings.TrimPrefix(f.srv.URL, "http") + "/api/v1/tunnel?" + query
	return websocket.Dial(ctx, url, &websocket.DialOptions{HTTPHeader: header})
}

func TestPing(t *testing.T) {
	f := newFixture(t)
	resp, body := f.do(t, http.MethodGet, "/api/v1/ping", "", nil)
	if resp.StatusCode != http.StatusOK || body["service"] != "book-recon" || body["version"] != "1.2.3" {
		t.Fatalf("ping: %d %v", resp.StatusCode, body)
	}
}

func TestStatusFlow(t *testing.T) {
	f := newFixture(t)
	token := f.register(t)

	resp, body := f.do(t, http.MethodGet, "/api/v1/clients/me", token, nil)
	if resp.StatusCode != http.StatusOK || body["status"] != "unlinked" {
		t.Fatalf("status before link: %d %v", resp.StatusCode, body)
	}

	resp, body = f.do(t, http.MethodPost, "/api/v1/clients/me/link", token, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("link status = %d", resp.StatusCode)
	}
	url, _ := body["url"].(string)
	if !strings.HasPrefix(url, "https://t.me/book_recon_bot?start=link_") {
		t.Fatalf("link url = %q", url)
	}
	if _, err := time.Parse(time.RFC3339, body["expiresAt"].(string)); err != nil {
		t.Fatalf("expiresAt: %v", err)
	}

	f.approve(t, token, 42)
	_, body = f.do(t, http.MethodGet, "/api/v1/clients/me", token, nil)
	if body["status"] != "approved" || body["telegramUsername"] != "reader" {
		t.Fatalf("status after approval: %v", body)
	}
	hosts, _ := body["tunnelHosts"].([]any)
	if len(hosts) != 2 || hosts[0] != "rutracker.org" {
		t.Fatalf("tunnel hosts = %v", body["tunnelHosts"])
	}
}

func TestDeleteForgetsTheClient(t *testing.T) {
	f := newFixture(t)
	token := f.register(t)
	resp, _ := f.do(t, http.MethodDelete, "/api/v1/clients/me", token, nil)
	if resp.StatusCode != http.StatusNoContent {
		t.Fatalf("delete status = %d", resp.StatusCode)
	}
	resp, _ = f.do(t, http.MethodGet, "/api/v1/clients/me", token, nil)
	if resp.StatusCode != http.StatusUnauthorized {
		t.Fatalf("status after delete = %d", resp.StatusCode)
	}
}

func TestAuthIsRequired(t *testing.T) {
	f := newFixture(t)
	for _, tc := range []struct{ method, path string }{
		{http.MethodGet, "/api/v1/clients/me"},
		{http.MethodPost, "/api/v1/clients/me/link"},
		{http.MethodDelete, "/api/v1/clients/me"},
	} {
		for _, token := range []string{"", "bogus"} {
			resp, body := f.do(t, tc.method, tc.path, token, nil)
			if resp.StatusCode != http.StatusUnauthorized || body["error"] != "unauthorized" {
				t.Fatalf("%s %s with %q: %d %v", tc.method, tc.path, token, resp.StatusCode, body)
			}
		}
	}
}

func TestRegisterIsRateLimited(t *testing.T) {
	f := newFixture(t, func(c *appapi.Config) { c.RegisterPerMinute = 2 })
	f.register(t)
	f.register(t)
	resp, _ := f.do(t, http.MethodPost, "/api/v1/clients", "", map[string]string{"deviceName": "x"})
	if resp.StatusCode != http.StatusTooManyRequests {
		t.Fatalf("third register status = %d", resp.StatusCode)
	}
}

func TestTunnelPipesBytes(t *testing.T) {
	f := newFixture(t)
	token := f.register(t)
	f.approve(t, token, 42)

	ws, _, err := f.dialTunnel(t, token, "host=rutracker.org&port=443")
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer func() { _ = ws.CloseNow() }()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	payload := bytes.Repeat([]byte("книга"), 10_000)
	if err := ws.Write(ctx, websocket.MessageBinary, payload); err != nil {
		t.Fatalf("write: %v", err)
	}
	var got []byte
	for len(got) < len(payload) {
		typ, data, err := ws.Read(ctx)
		if err != nil {
			t.Fatalf("read after %d bytes: %v", len(got), err)
		}
		if typ != websocket.MessageBinary {
			t.Fatalf("message type = %v", typ)
		}
		got = append(got, data...)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("echoed bytes differ")
	}
}

func TestTunnelRejections(t *testing.T) {
	f := newFixture(t)
	pending := f.register(t)
	approved := f.register(t)
	f.approve(t, approved, 42)

	tests := []struct {
		name, token, query string
		code               int
	}{
		{"no token", "", "host=rutracker.org&port=443", http.StatusUnauthorized},
		{"not approved", pending, "host=rutracker.org&port=443", http.StatusForbidden},
		{"host not allowed", approved, "host=example.com&port=443", http.StatusForbidden},
		{"suffix trick", approved, "host=evilrutracker.org&port=443", http.StatusForbidden},
		{"port not allowed", approved, "host=rutracker.org&port=22", http.StatusForbidden},
		{"bad port", approved, "host=rutracker.org&port=x", http.StatusBadRequest},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			ws, resp, err := f.dialTunnel(t, tt.token, tt.query)
			if err == nil {
				_ = ws.CloseNow()
				t.Fatal("expected the tunnel to be refused")
			}
			if resp == nil || resp.StatusCode != tt.code {
				t.Fatalf("status = %v, want %d", resp, tt.code)
			}
		})
	}
}

func TestTunnelWithoutUpgradeHeadersIs426(t *testing.T) {
	f := newFixture(t)
	// A reverse proxy that drops Upgrade/Connection turns the tunnel into a plain GET: the
	// answer must say so before the token is even looked at.
	for _, token := range []string{"", "bogus"} {
		resp, body := f.do(t, http.MethodGet, "/api/v1/tunnel?host=rutracker.org&port=443", token, nil)
		if resp.StatusCode != http.StatusUpgradeRequired || body["error"] != "websocket_required" {
			t.Fatalf("token %q: %d %v", token, resp.StatusCode, body)
		}
	}
}

func TestTunnelAllowsSubdomains(t *testing.T) {
	f := newFixture(t)
	token := f.register(t)
	f.approve(t, token, 42)
	ws, _, err := f.dialTunnel(t, token, "host=static.rutracker.org&port=443")
	if err != nil {
		t.Fatalf("subdomain must be allowed: %v", err)
	}
	_ = ws.CloseNow()
}

func TestIsPublicIP(t *testing.T) {
	tests := map[string]bool{
		"104.21.32.39": true,
		"2606:4700::1": true,
		"127.0.0.1":    false,
		"10.0.0.5":     false,
		"192.168.1.1":  false,
		"172.17.0.2":   false,
		"169.254.1.1":  false,
		"::1":          false,
		"fd00::1":      false,
		"0.0.0.0":      false,
	}
	for addr, want := range tests {
		if got := appapi.IsPublicIP(net.ParseIP(addr)); got != want {
			t.Errorf("IsPublicIP(%s) = %v, want %v", addr, got, want)
		}
	}
}

func TestTunnelToPeers(t *testing.T) {
	f := newFixture(t, func(c *appapi.Config) { c.AllowPeers = true })
	token := f.register(t)
	f.approve(t, token, 42)

	ws, _, err := f.dialTunnel(t, token, "host=104.21.32.39&port=51413")
	if err != nil {
		t.Fatalf("a public peer must be allowed: %v", err)
	}
	_ = ws.CloseNow()
	ws, _, err = f.dialTunnel(t, token, "host=2606:4700::1&port=6881")
	if err != nil {
		t.Fatalf("an IPv6 peer must be allowed: %v", err)
	}
	_ = ws.CloseNow()

	for _, query := range []string{
		"host=10.0.0.5&port=51413",    // private network of the server
		"host=127.0.0.1&port=51413",   // the server itself
		"host=104.21.32.39&port=25",   // well-known port
		"host=104.21.32.39&port=1023", // still well-known
	} {
		ws, resp, err := f.dialTunnel(t, token, query)
		if err == nil {
			_ = ws.CloseNow()
			t.Fatalf("%s: expected the tunnel to be refused", query)
		}
		if resp == nil || resp.StatusCode != http.StatusForbidden {
			t.Fatalf("%s: status = %v, want 403", query, resp)
		}
	}
}

func TestTunnelToPeersIsOffByDefault(t *testing.T) {
	f := newFixture(t)
	token := f.register(t)
	f.approve(t, token, 42)
	ws, resp, err := f.dialTunnel(t, token, "host=104.21.32.39&port=51413")
	if err == nil {
		_ = ws.CloseNow()
		t.Fatal("expected the tunnel to be refused")
	}
	if resp == nil || resp.StatusCode != http.StatusForbidden {
		t.Fatalf("status = %v, want 403", resp)
	}
}

type fakeTracker struct {
	kinds []domain.TrackerKind
}

func (f *fakeTracker) Search(_ context.Context, query string, kind domain.TrackerKind, limit int) ([]domain.TrackerTopic, error) {
	f.kinds = append(f.kinds, kind)
	if query == "down" {
		return nil, domain.NewError(domain.ErrCodeServiceDown, "jackett unavailable")
	}
	return []domain.TrackerTopic{{ID: "6543210", Title: query, Size: 1024, Seeders: 7, Peers: limit}}, nil
}

func (f *fakeTracker) FetchTorrent(_ context.Context, topicID string) ([]byte, error) {
	if topicID != "6543210" {
		return nil, domain.NewError(domain.ErrCodeNotFound, "unknown topic")
	}
	return []byte("d8:announce0:e"), nil
}

func TestRuTrackerSearch(t *testing.T) {
	tracker := &fakeTracker{}
	f := newFixture(t, func(c *appapi.Config) { c.Tracker = tracker })
	token := f.register(t)
	f.approve(t, token, 42)

	resp, body := f.do(t, http.MethodGet, "/api/v1/rutracker/search?q=%D0%B4%D0%BE%D0%B7%D0%BE%D1%80&kind=audio&limit=5", token, nil)
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("search status = %d %v", resp.StatusCode, body)
	}
	topics, _ := body["topics"].([]any)
	if len(topics) != 1 {
		t.Fatalf("topics = %v", body)
	}
	topic := topics[0].(map[string]any)
	if topic["topicId"] != "6543210" || topic["title"] != "дозор" || topic["size"] != 1024.0 ||
		topic["seeders"] != 7.0 || topic["peers"] != 5.0 {
		t.Fatalf("topic = %v", topic)
	}
	if len(tracker.kinds) != 1 || tracker.kinds[0] != domain.TrackerKindAudio {
		t.Fatalf("kinds = %v", tracker.kinds)
	}
}

func TestRuTrackerSearchRejections(t *testing.T) {
	f := newFixture(t, func(c *appapi.Config) { c.Tracker = &fakeTracker{} })
	pending := f.register(t)
	approved := f.register(t)
	f.approve(t, approved, 42)

	tests := []struct {
		name, token, query, reason string
		code                       int
	}{
		{"no token", "", "q=abc&kind=book", "unauthorized", http.StatusUnauthorized},
		{"not approved", pending, "q=abc&kind=book", "not_approved", http.StatusForbidden},
		{"empty query", approved, "q=%20&kind=book", "bad_request", http.StatusBadRequest},
		{"unknown kind", approved, "q=abc&kind=film", "bad_request", http.StatusBadRequest},
		{"jackett down", approved, "q=down&kind=book", "rutracker_unavailable", http.StatusBadGateway},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			resp, body := f.do(t, http.MethodGet, "/api/v1/rutracker/search?"+tt.query, tt.token, nil)
			if resp.StatusCode != tt.code || body["error"] != tt.reason {
				t.Fatalf("got %d %v, want %d %s", resp.StatusCode, body, tt.code, tt.reason)
			}
		})
	}
}

func TestRuTrackerIsOffWithoutTracker(t *testing.T) {
	f := newFixture(t)
	token := f.register(t)
	f.approve(t, token, 42)
	for _, path := range []string{"/api/v1/rutracker/search?q=abc&kind=book", "/api/v1/rutracker/topics/6543210/torrent"} {
		resp, body := f.do(t, http.MethodGet, path, token, nil)
		if resp.StatusCode != http.StatusServiceUnavailable || body["error"] != "rutracker_disabled" {
			t.Fatalf("%s: %d %v", path, resp.StatusCode, body)
		}
	}
}

func TestRuTrackerTorrent(t *testing.T) {
	f := newFixture(t, func(c *appapi.Config) { c.Tracker = &fakeTracker{} })
	token := f.register(t)
	f.approve(t, token, 42)

	req, _ := http.NewRequest(http.MethodGet, f.srv.URL+"/api/v1/rutracker/topics/6543210/torrent", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("get torrent: %v", err)
	}
	data, _ := io.ReadAll(resp.Body)
	_ = resp.Body.Close()
	if resp.StatusCode != http.StatusOK || string(data) != "d8:announce0:e" ||
		resp.Header.Get("Content-Type") != "application/x-bittorrent" {
		t.Fatalf("torrent: %d %q %s", resp.StatusCode, data, resp.Header.Get("Content-Type"))
	}

	r, body := f.do(t, http.MethodGet, "/api/v1/rutracker/topics/42/torrent", token, nil)
	if r.StatusCode != http.StatusNotFound || body["error"] != "topic_unknown" {
		t.Fatalf("unknown topic: %d %v", r.StatusCode, body)
	}
	r, body = f.do(t, http.MethodGet, "/api/v1/rutracker/topics/abc/torrent", token, nil)
	if r.StatusCode != http.StatusBadRequest || body["error"] != "bad_request" {
		t.Fatalf("bad topic id: %d %v", r.StatusCode, body)
	}
}
