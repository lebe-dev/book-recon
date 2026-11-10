package usecase

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

const (
	// LinkCodeTTL is how long a Telegram link code stays valid.
	LinkCodeTTL = 15 * time.Minute
	// UnlinkedClientTTL is how long a client that never linked a Telegram account is kept.
	UnlinkedClientTTL = 24 * time.Hour
	// LinkCodePrefix starts the /start payload of a link code, so the bot can tell it apart.
	LinkCodePrefix = "link_"

	maxDeviceNameLength = 64
	touchInterval       = time.Minute
)

var (
	ErrUnauthorized    = errors.New("unknown app client token")
	ErrLinkCodeInvalid = errors.New("link code is unknown or expired")
)

// TelegramUser is the Telegram account that sent a link code to the bot.
type TelegramUser struct {
	ID        int64
	Username  string
	FirstName string
}

// LinkResult tells the bot what to answer after a link.
type LinkResult struct {
	Client *domain.AppClient
	Status domain.AppClientStatus
	// NewRequest is true when an access request was created and admins must be notified.
	NewRequest bool
}

// AppClientService registers Android app installations, links them to Telegram accounts and
// decides whether they may use the tunnel. Access follows the bot: a user approved there is
// approved in the app, a denied one is denied in both.
type AppClientService struct {
	repo   domain.AppClientRepository
	access *AccessService
	logger *log.Logger
	now    func() time.Time
}

func NewAppClientService(repo domain.AppClientRepository, access *AccessService, logger *log.Logger) *AppClientService {
	return &AppClientService{repo: repo, access: access, logger: logger, now: time.Now}
}

// SetClock replaces the clock; tests use it.
func (s *AppClientService) SetClock(now func() time.Time) { s.now = now }

// Register creates a client and returns its bearer token. The token is shown only once:
// the server keeps its hash.
func (s *AppClientService) Register(ctx context.Context, deviceName string) (string, error) {
	now := s.now()
	if n, err := s.repo.DeleteUnlinkedBefore(ctx, now.Add(-UnlinkedClientTTL)); err != nil {
		s.logger.Warn("failed to purge stale app clients", "error", err)
	} else if n > 0 {
		s.logger.Info("purged app clients that were never linked", "count", n)
	}

	token, err := randomToken(32)
	if err != nil {
		return "", err
	}
	id, err := randomHex(16)
	if err != nil {
		return "", err
	}
	client := domain.AppClient{
		ID:         id,
		TokenHash:  hashToken(token),
		DeviceName: cleanDeviceName(deviceName),
		CreatedAt:  now,
		LastSeenAt: now,
	}
	if err := s.repo.Create(ctx, client); err != nil {
		return "", fmt.Errorf("create app client: %w", err)
	}
	s.logger.Info("app client registered", "client_id", id, "device", client.DeviceName)
	return token, nil
}

// Authenticate finds the client of a bearer token.
func (s *AppClientService) Authenticate(ctx context.Context, token string) (*domain.AppClient, error) {
	if token == "" {
		return nil, ErrUnauthorized
	}
	client, err := s.repo.GetByTokenHash(ctx, hashToken(token))
	if err != nil {
		return nil, err
	}
	if client == nil {
		return nil, ErrUnauthorized
	}
	if now := s.now(); now.Sub(client.LastSeenAt) >= touchInterval {
		if err := s.repo.Touch(ctx, client.ID, now); err != nil {
			s.logger.Warn("failed to update app client last seen time", "client_id", client.ID, "error", err)
		}
	}
	return client, nil
}

// IssueLinkCode replaces the client's link code with a new one.
func (s *AppClientService) IssueLinkCode(ctx context.Context, client *domain.AppClient) (string, time.Time, error) {
	random, err := randomHex(16)
	if err != nil {
		return "", time.Time{}, err
	}
	code := LinkCodePrefix + random
	expires := s.now().Add(LinkCodeTTL)
	if err := s.repo.SetLinkCode(ctx, client.ID, code, expires); err != nil {
		return "", time.Time{}, fmt.Errorf("store link code: %w", err)
	}
	return code, expires, nil
}

// LinkTelegram binds the client holding the code to the Telegram account. An unknown user
// gets a pending access request; a denied one stays denied and no request is created.
func (s *AppClientService) LinkTelegram(ctx context.Context, code string, user TelegramUser) (*LinkResult, error) {
	client, err := s.repo.GetByLinkCode(ctx, code)
	if err != nil {
		return nil, err
	}
	if client == nil || !s.now().Before(client.LinkExpiresAt) {
		return nil, ErrLinkCodeInvalid
	}
	if err := s.repo.Link(ctx, client.ID, user.ID, user.Username); err != nil {
		return nil, fmt.Errorf("link app client: %w", err)
	}
	client.TelegramID, client.Username, client.LinkCode = user.ID, user.Username, ""
	s.logger.Info("app client linked to telegram", "client_id", client.ID, "telegram_id", user.ID)

	status, err := s.access.EffectiveStatus(ctx, user.ID, user.Username)
	if err != nil {
		return nil, err
	}
	if status != "" {
		return &LinkResult{Client: client, Status: toClientStatus(status)}, nil
	}

	created, err := s.access.RequestAccess(ctx, domain.AccessRequest{
		TelegramID: user.ID,
		Username:   user.Username,
		FirstName:  user.FirstName,
	})
	if err != nil {
		return nil, err
	}
	return &LinkResult{Client: client, Status: domain.AppClientPending, NewRequest: created}, nil
}

// Status is what the app shows. A linked client whose access record was revoked is unlinked,
// so the user can link again and send a new request.
func (s *AppClientService) Status(ctx context.Context, client *domain.AppClient) (domain.AppClientStatus, error) {
	if !client.Linked() {
		return domain.AppClientUnlinked, nil
	}
	status, err := s.access.EffectiveStatus(ctx, client.TelegramID, client.Username)
	if err != nil {
		return "", err
	}
	if status != "" {
		return toClientStatus(status), nil
	}
	if err := s.repo.Unlink(ctx, client.ID); err != nil {
		return "", fmt.Errorf("unlink revoked app client: %w", err)
	}
	s.logger.Info("app client unlinked after access was revoked", "client_id", client.ID, "telegram_id", client.TelegramID)
	client.TelegramID, client.Username = 0, ""
	return domain.AppClientUnlinked, nil
}

// CanTunnel reports whether the client may open tunnels.
func (s *AppClientService) CanTunnel(ctx context.Context, client *domain.AppClient) (bool, error) {
	status, err := s.Status(ctx, client)
	if err != nil {
		return false, err
	}
	return status == domain.AppClientApproved, nil
}

// Delete removes the client; the app forgets its token too.
func (s *AppClientService) Delete(ctx context.Context, client *domain.AppClient) error {
	if err := s.repo.Delete(ctx, client.ID); err != nil {
		return err
	}
	s.logger.Info("app client removed", "client_id", client.ID)
	return nil
}

// LinkedCounts returns the number of linked app clients per Telegram ID.
func (s *AppClientService) LinkedCounts(ctx context.Context) (map[int64]int, error) {
	return s.repo.CountLinked(ctx)
}

func toClientStatus(status domain.AccessStatus) domain.AppClientStatus {
	switch status {
	case domain.AccessStatusApproved:
		return domain.AppClientApproved
	case domain.AccessStatusDenied:
		return domain.AppClientDenied
	default:
		return domain.AppClientPending
	}
}

func hashToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}

func randomToken(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("generate token: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

func randomHex(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("generate id: %w", err)
	}
	return hex.EncodeToString(b), nil
}

func cleanDeviceName(name string) string {
	name = strings.Join(strings.Fields(name), " ")
	if r := []rune(name); len(r) > maxDeviceNameLength {
		name = string(r[:maxDeviceNameLength])
	}
	return name
}
