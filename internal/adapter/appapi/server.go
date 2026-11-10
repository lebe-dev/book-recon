// Package appapi is the HTTP API of the Android app: client registration, Telegram linking
// and a WebSocket tunnel to an allowlist of hosts, so the app reaches sites its network
// blocks. It serves plain HTTP and is meant to sit behind a reverse proxy that terminates TLS.
package appapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/charmbracelet/log"
	"github.com/coder/websocket"
	"github.com/lebe-dev/book-recon/internal/domain"
	"github.com/lebe-dev/book-recon/internal/usecase"
)

const (
	defaultRegisterPerMinute   = 10
	defaultMaxTunnelsPerClient = 64
	defaultSearchLimit         = 20
	maxSearchLimit             = 50
	maxQueryLength             = 200
	searchTimeout              = 90 * time.Second
	// minPeerPort keeps peer tunnels off well-known ports (SMTP, SSH and the like).
	minPeerPort    = 1024
	maxRequestBody = 4 << 10
	maxMessageSize = 1 << 20
	dialTimeout    = 15 * time.Second
)

// DialFunc opens the TCP connection of a tunnel.
type DialFunc func(ctx context.Context, network, addr string) (net.Conn, error)

type Config struct {
	Listen string
	// AllowedHosts are domains the tunnel may reach, subdomains included.
	AllowedHosts []string
	AllowedPorts []int
	// BotUsername returns the bot's username for t.me links.
	BotUsername func() string
	Version     string
	// RegisterPerMinute caps client registrations across all callers; 0 means the default.
	RegisterPerMinute int
	// MaxTunnelsPerClient caps open tunnels of one client; 0 means the default.
	MaxTunnelsPerClient int
	// AllowPeers lets the tunnel reach BitTorrent peers: public IP addresses given as
	// literals, on ports from 1024 up.
	AllowPeers bool
	// Tracker serves RuTracker search with the server's account; nil turns it off.
	Tracker domain.TopicTracker
	// Dial opens tunnel connections; nil means DialPublic.
	Dial DialFunc
}

type Server struct {
	cfg     Config
	clients *usecase.AppClientService
	logger  *log.Logger
	server  *http.Server

	// tunnels lives until Shutdown: hijacked connections are not closed by http.Server.
	tunnels context.Context
	stop    context.CancelFunc

	mu          sync.Mutex
	open        map[string]int
	windowStart time.Time
	registered  int
}

func NewServer(cfg Config, clients *usecase.AppClientService, logger *log.Logger) *Server {
	if cfg.RegisterPerMinute <= 0 {
		cfg.RegisterPerMinute = defaultRegisterPerMinute
	}
	if cfg.MaxTunnelsPerClient <= 0 {
		cfg.MaxTunnelsPerClient = defaultMaxTunnelsPerClient
	}
	if cfg.Dial == nil {
		cfg.Dial = DialPublic
	}
	cfg.AllowedHosts = normalizeHosts(cfg.AllowedHosts)

	s := &Server{cfg: cfg, clients: clients, logger: logger, open: make(map[string]int)}
	s.tunnels, s.stop = context.WithCancel(context.Background())

	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/ping", s.handlePing)
	mux.HandleFunc("POST /api/v1/clients", s.handleRegister)
	mux.HandleFunc("GET /api/v1/clients/me", s.authenticated(s.handleStatus))
	mux.HandleFunc("DELETE /api/v1/clients/me", s.authenticated(s.handleDelete))
	mux.HandleFunc("POST /api/v1/clients/me/link", s.authenticated(s.handleLink))
	mux.HandleFunc("GET /api/v1/tunnel", requireWebSocket(s.authenticated(s.handleTunnel)))
	mux.HandleFunc("GET /api/v1/rutracker/search", s.withTracker(s.authenticated(s.approved(s.handleSearch))))
	mux.HandleFunc("GET /api/v1/rutracker/topics/{id}/torrent", s.withTracker(s.authenticated(s.approved(s.handleTorrent))))

	s.server = &http.Server{
		Addr:              cfg.Listen,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
	}
	return s
}

// Handler exposes the router (used by tests).
func (s *Server) Handler() http.Handler { return s.server.Handler }

// Start serves until Shutdown. Returns nil on graceful shutdown.
func (s *Server) Start() error {
	s.logger.Info("app api listening", "addr", s.server.Addr, "tunnel_hosts", s.cfg.AllowedHosts,
		"tunnel_peers", s.cfg.AllowPeers, "rutracker_search", s.cfg.Tracker != nil)
	if err := s.server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

// Shutdown stops accepting requests and closes open tunnels.
func (s *Server) Shutdown(ctx context.Context) error {
	s.stop()
	return s.server.Shutdown(ctx)
}

// requireWebSocket answers 426 to a tunnel request that is not a WebSocket upgrade, before
// authentication: a reverse proxy that drops the Upgrade headers then shows up as 426 instead
// of looking like a token problem.
func requireWebSocket(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
			writeError(w, http.StatusUpgradeRequired, "websocket_required")
			return
		}
		next(w, r)
	}
}

type clientHandler func(w http.ResponseWriter, r *http.Request, client *domain.AppClient)

func (s *Server) authenticated(next clientHandler) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		client, err := s.clients.Authenticate(r.Context(), strings.TrimSpace(token))
		if errors.Is(err, usecase.ErrUnauthorized) {
			writeError(w, http.StatusUnauthorized, "unauthorized")
			return
		}
		if err != nil {
			s.logger.Error("failed to authenticate app client", "error", err)
			writeError(w, http.StatusInternalServerError, "internal")
			return
		}
		next(w, r, client)
	}
}

func (s *Server) handlePing(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"service": "book-recon", "version": s.cfg.Version, "api": 1})
}

func (s *Server) handleRegister(w http.ResponseWriter, r *http.Request) {
	if !s.allowRegistration() {
		writeError(w, http.StatusTooManyRequests, "too_many_requests")
		return
	}
	var body struct {
		DeviceName string `json:"deviceName"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, maxRequestBody)).Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "bad_request")
		return
	}
	token, err := s.clients.Register(r.Context(), body.DeviceName)
	if err != nil {
		s.logger.Error("failed to register app client", "error", err)
		writeError(w, http.StatusInternalServerError, "internal")
		return
	}
	writeJSON(w, http.StatusCreated, map[string]string{"token": token})
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request, client *domain.AppClient) {
	status, err := s.clients.Status(r.Context(), client)
	if err != nil {
		s.logger.Error("failed to get app client status", "client_id", client.ID, "error", err)
		writeError(w, http.StatusInternalServerError, "internal")
		return
	}
	resp := map[string]any{"status": status, "tunnelHosts": s.cfg.AllowedHosts}
	if client.Username != "" {
		resp["telegramUsername"] = client.Username
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) handleDelete(w http.ResponseWriter, r *http.Request, client *domain.AppClient) {
	if err := s.clients.Delete(r.Context(), client); err != nil {
		s.logger.Error("failed to delete app client", "client_id", client.ID, "error", err)
		writeError(w, http.StatusInternalServerError, "internal")
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (s *Server) handleLink(w http.ResponseWriter, r *http.Request, client *domain.AppClient) {
	code, expires, err := s.clients.IssueLinkCode(r.Context(), client)
	if err != nil {
		s.logger.Error("failed to issue link code", "client_id", client.ID, "error", err)
		writeError(w, http.StatusInternalServerError, "internal")
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{
		"code":      code,
		"url":       "https://t.me/" + s.cfg.BotUsername() + "?start=" + code,
		"expiresAt": expires.UTC().Format(time.RFC3339),
	})
}

func (s *Server) handleTunnel(w http.ResponseWriter, r *http.Request, client *domain.AppClient) {
	host := strings.TrimSuffix(strings.ToLower(r.URL.Query().Get("host")), ".")
	port, err := strconv.Atoi(r.URL.Query().Get("port"))
	if host == "" || err != nil || port <= 0 || port > 65535 {
		writeError(w, http.StatusBadRequest, "bad_request")
		return
	}
	ok, err := s.clients.CanTunnel(r.Context(), client)
	if err != nil {
		s.logger.Error("failed to check tunnel access", "client_id", client.ID, "error", err)
		writeError(w, http.StatusInternalServerError, "internal")
		return
	}
	if !ok {
		writeError(w, http.StatusForbidden, "not_approved")
		return
	}
	if !s.targetAllowed(host, port) {
		s.logger.Warn("tunnel to a host outside the allowlist refused", "client_id", client.ID, "host", host, "port", port)
		writeError(w, http.StatusForbidden, "host_not_allowed")
		return
	}
	if !s.acquireTunnel(client.ID) {
		writeError(w, http.StatusTooManyRequests, "too_many_tunnels")
		return
	}
	defer s.releaseTunnel(client.ID)

	addr := net.JoinHostPort(host, strconv.Itoa(port))
	dialCtx, cancel := context.WithTimeout(r.Context(), dialTimeout)
	target, err := s.cfg.Dial(dialCtx, "tcp", addr)
	cancel()
	if err != nil {
		s.logger.Warn("failed to reach tunnel target", "host", host, "port", port, "error", err)
		writeError(w, http.StatusBadGateway, "unreachable")
		return
	}
	defer func() { _ = target.Close() }()

	ws, err := websocket.Accept(w, r, nil)
	if err != nil {
		s.logger.Warn("failed to accept tunnel websocket", "error", err)
		return
	}
	defer func() { _ = ws.CloseNow() }()
	ws.SetReadLimit(maxMessageSize)

	s.logger.Debug("tunnel opened", "client_id", client.ID, "host", host, "port", port)
	pipe(s.tunnels, ws, target)
	s.logger.Debug("tunnel closed", "client_id", client.ID, "host", host, "port", port)
}

// pipe copies bytes both ways until one side closes or ctx ends.
func pipe(ctx context.Context, ws *websocket.Conn, target net.Conn) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	conn := websocket.NetConn(ctx, ws, websocket.MessageBinary)
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(target, conn)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(conn, target)
		done <- struct{}{}
	}()
	select {
	case <-done:
	case <-ctx.Done():
	}
	_ = conn.Close()
	_ = target.Close()
}

// targetAllowed accepts a host of the allowlist on an allowed port, or, with AllowPeers, a
// public IP literal on a port from minPeerPort up.
func (s *Server) targetAllowed(host string, port int) bool {
	if ip := net.ParseIP(host); ip != nil {
		return s.cfg.AllowPeers && IsPublicIP(ip) && port >= minPeerPort
	}
	return s.hostAllowed(host) && slices.Contains(s.cfg.AllowedPorts, port)
}

// withTracker answers 503 when the server has no RuTracker search.
func (s *Server) withTracker(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s.cfg.Tracker == nil {
			writeError(w, http.StatusServiceUnavailable, "rutracker_disabled")
			return
		}
		next(w, r)
	}
}

// approved lets through clients the admin has approved.
func (s *Server) approved(next clientHandler) clientHandler {
	return func(w http.ResponseWriter, r *http.Request, client *domain.AppClient) {
		ok, err := s.clients.CanTunnel(r.Context(), client)
		if err != nil {
			s.logger.Error("failed to check app client access", "client_id", client.ID, "error", err)
			writeError(w, http.StatusInternalServerError, "internal")
			return
		}
		if !ok {
			writeError(w, http.StatusForbidden, "not_approved")
			return
		}
		next(w, r, client)
	}
}

type topicJSON struct {
	TopicID string `json:"topicId"`
	Title   string `json:"title"`
	Size    int64  `json:"size"`
	Seeders int    `json:"seeders"`
	Peers   int    `json:"peers"`
}

func (s *Server) handleSearch(w http.ResponseWriter, r *http.Request, client *domain.AppClient) {
	q := r.URL.Query()
	query := strings.TrimSpace(q.Get("q"))
	kind := domain.TrackerKind(q.Get("kind"))
	limit := defaultSearchLimit
	if raw := q.Get("limit"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || n <= 0 {
			writeError(w, http.StatusBadRequest, "bad_request")
			return
		}
		limit = min(n, maxSearchLimit)
	}
	if query == "" || len([]rune(query)) > maxQueryLength || (kind != domain.TrackerKindBook && kind != domain.TrackerKindAudio) {
		writeError(w, http.StatusBadRequest, "bad_request")
		return
	}

	ctx, cancel := context.WithTimeout(r.Context(), searchTimeout)
	defer cancel()
	topics, err := s.cfg.Tracker.Search(ctx, query, kind, limit)
	if err != nil {
		s.logger.Warn("rutracker search for the app failed", "client_id", client.ID, "error", err)
		writeError(w, http.StatusBadGateway, "rutracker_unavailable")
		return
	}
	out := make([]topicJSON, 0, len(topics))
	for _, t := range topics {
		out = append(out, topicJSON{TopicID: t.ID, Title: t.Title, Size: t.Size, Seeders: t.Seeders, Peers: t.Peers})
	}
	writeJSON(w, http.StatusOK, map[string]any{"topics": out})
}

func (s *Server) handleTorrent(w http.ResponseWriter, r *http.Request, client *domain.AppClient) {
	id := r.PathValue("id")
	if _, err := strconv.ParseUint(id, 10, 64); err != nil {
		writeError(w, http.StatusBadRequest, "bad_request")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), searchTimeout)
	defer cancel()
	data, err := s.cfg.Tracker.FetchTorrent(ctx, id)
	var de *domain.DomainError
	switch {
	case errors.As(err, &de) && de.Code == domain.ErrCodeNotFound:
		writeError(w, http.StatusNotFound, "topic_unknown")
		return
	case err != nil:
		s.logger.Warn("failed to fetch a torrent for the app", "client_id", client.ID, "topic_id", id, "error", err)
		writeError(w, http.StatusBadGateway, "rutracker_unavailable")
		return
	}
	w.Header().Set("Content-Type", "application/x-bittorrent")
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

func (s *Server) hostAllowed(host string) bool {
	for _, allowed := range s.cfg.AllowedHosts {
		if host == allowed || strings.HasSuffix(host, "."+allowed) {
			return true
		}
	}
	return false
}

func (s *Server) allowRegistration() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now()
	if now.Sub(s.windowStart) >= time.Minute {
		s.windowStart, s.registered = now, 0
	}
	if s.registered >= s.cfg.RegisterPerMinute {
		return false
	}
	s.registered++
	return true
}

func (s *Server) acquireTunnel(clientID string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.open[clientID] >= s.cfg.MaxTunnelsPerClient {
		return false
	}
	s.open[clientID]++
	return true
}

func (s *Server) releaseTunnel(clientID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.open[clientID]--; s.open[clientID] <= 0 {
		delete(s.open, clientID)
	}
}

// DialPublic resolves the host and connects only to public addresses, so a DNS record of an
// allowed domain cannot point the tunnel into the server's own network.
func DialPublic(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	ips, err := net.DefaultResolver.LookupIPAddr(ctx, host)
	if err != nil {
		return nil, err
	}
	// IPv4 first: containers often resolve AAAA records without having an IPv6 route.
	slices.SortStableFunc(ips, func(a, b net.IPAddr) int {
		return boolToInt(a.IP.To4() == nil) - boolToInt(b.IP.To4() == nil)
	})
	dialer := net.Dialer{Timeout: 5 * time.Second}
	lastErr := errors.New("no public address for " + host)
	for _, ip := range ips {
		if !IsPublicIP(ip.IP) {
			continue
		}
		conn, err := dialer.DialContext(ctx, network, net.JoinHostPort(ip.IP.String(), port))
		if err == nil {
			return conn, nil
		}
		lastErr = err
	}
	return nil, lastErr
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

var sharedAddressSpace = &net.IPNet{IP: net.IPv4(100, 64, 0, 0), Mask: net.CIDRMask(10, 32)}

// IsPublicIP reports whether ip is a globally routable unicast address.
func IsPublicIP(ip net.IP) bool {
	return ip.IsGlobalUnicast() && !ip.IsPrivate() && !ip.IsLoopback() && !ip.IsLinkLocalUnicast() &&
		!sharedAddressSpace.Contains(ip)
}

func normalizeHosts(hosts []string) []string {
	out := make([]string, 0, len(hosts))
	for _, h := range hosts {
		h = strings.Trim(strings.ToLower(strings.TrimSpace(h)), ".")
		if h != "" && !slices.Contains(out, h) {
			out = append(out, h)
		}
	}
	return out
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, code int, reason string) {
	writeJSON(w, code, map[string]string{"error": reason})
}
