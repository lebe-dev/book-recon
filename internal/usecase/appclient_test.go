package usecase_test

import (
	"context"
	"errors"
	"io"
	"testing"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/adapter/storage"
	"github.com/lebe-dev/book-recon/internal/domain"
	"github.com/lebe-dev/book-recon/internal/usecase"
)

type appClientFixture struct {
	svc    *usecase.AppClientService
	access *storage.AccessRepo
	repo   *storage.AppClientRepo
	now    time.Time
}

func newAppClientFixture(t *testing.T) *appClientFixture {
	t.Helper()
	db, err := storage.NewDB(":memory:")
	if err != nil {
		t.Fatalf("open db: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })

	logger := log.New(io.Discard)
	accessRepo := storage.NewAccessRepo(db)
	access := usecase.NewAccessService(accessRepo, storage.NewUserRegistryRepo(db), []string{"friend"}, []string{"boss"}, logger)
	f := &appClientFixture{access: accessRepo, repo: storage.NewAppClientRepo(db), now: time.Date(2026, 11, 10, 9, 0, 0, 0, time.UTC)}
	f.svc = usecase.NewAppClientService(f.repo, access, logger)
	f.svc.SetClock(func() time.Time { return f.now })
	return f
}

func (f *appClientFixture) register(t *testing.T) (string, *domain.AppClient) {
	t.Helper()
	ctx := context.Background()
	token, err := f.svc.Register(ctx, "Pixel 11 Pro")
	if err != nil {
		t.Fatalf("register: %v", err)
	}
	client, err := f.svc.Authenticate(ctx, token)
	if err != nil {
		t.Fatalf("authenticate: %v", err)
	}
	return token, client
}

func (f *appClientFixture) link(t *testing.T, client *domain.AppClient, telegramID int64, username string) {
	t.Helper()
	ctx := context.Background()
	code, _, err := f.svc.IssueLinkCode(ctx, client)
	if err != nil {
		t.Fatalf("issue code: %v", err)
	}
	if _, err := f.svc.LinkTelegram(ctx, code, usecase.TelegramUser{ID: telegramID, Username: username}); err != nil {
		t.Fatalf("link: %v", err)
	}
}

func (f *appClientFixture) status(t *testing.T, token string) domain.AppClientStatus {
	t.Helper()
	ctx := context.Background()
	client, err := f.svc.Authenticate(ctx, token)
	if err != nil {
		t.Fatalf("authenticate: %v", err)
	}
	status, err := f.svc.Status(ctx, client)
	if err != nil {
		t.Fatalf("status: %v", err)
	}
	return status
}

func TestAppClient_RegisterIssuesUniqueTokens(t *testing.T) {
	f := newAppClientFixture(t)
	a, clientA := f.register(t)
	b, clientB := f.register(t)
	if a == b || clientA.ID == clientB.ID {
		t.Fatal("tokens and IDs must be unique")
	}
	if clientA.TokenHash == a {
		t.Fatal("the token must not be stored as is")
	}
	if clientA.DeviceName != "Pixel 11 Pro" {
		t.Fatalf("device name = %q", clientA.DeviceName)
	}
}

func TestAppClient_AuthenticateRejectsUnknownToken(t *testing.T) {
	f := newAppClientFixture(t)
	f.register(t)
	for _, token := range []string{"", "garbage"} {
		if _, err := f.svc.Authenticate(context.Background(), token); !errors.Is(err, usecase.ErrUnauthorized) {
			t.Fatalf("token %q: expected ErrUnauthorized, got %v", token, err)
		}
	}
}

func TestAppClient_UnlinkedUntilTelegramLink(t *testing.T) {
	f := newAppClientFixture(t)
	token, _ := f.register(t)
	if got := f.status(t, token); got != domain.AppClientUnlinked {
		t.Fatalf("status = %q, want unlinked", got)
	}
}

func TestAppClient_LinkUnknownUserCreatesPendingRequest(t *testing.T) {
	f := newAppClientFixture(t)
	token, client := f.register(t)
	ctx := context.Background()
	code, expires, err := f.svc.IssueLinkCode(ctx, client)
	if err != nil {
		t.Fatalf("issue: %v", err)
	}
	if !expires.Equal(f.now.Add(usecase.LinkCodeTTL)) {
		t.Fatalf("expires = %v", expires)
	}

	result, err := f.svc.LinkTelegram(ctx, code, usecase.TelegramUser{ID: 100, Username: "stranger"})
	if err != nil {
		t.Fatalf("link: %v", err)
	}
	if result.Status != domain.AppClientPending || !result.NewRequest {
		t.Fatalf("link result = %+v, want a new pending request", result)
	}
	if got, _ := f.access.GetStatus(ctx, 100); got != domain.AccessStatusPending {
		t.Fatalf("access status = %q, want pending", got)
	}
	if got := f.status(t, token); got != domain.AppClientPending {
		t.Fatalf("client status = %q, want pending", got)
	}

	// The admin approves in the bot: the app follows.
	_ = f.access.SetStatus(ctx, 100, domain.AccessStatusApproved)
	if got := f.status(t, token); got != domain.AppClientApproved {
		t.Fatalf("client status = %q, want approved", got)
	}
}

func TestAppClient_StaticListsApproveRightAway(t *testing.T) {
	f := newAppClientFixture(t)
	for _, username := range []string{"Friend", "boss"} {
		token, client := f.register(t)
		ctx := context.Background()
		code, _, _ := f.svc.IssueLinkCode(ctx, client)
		result, err := f.svc.LinkTelegram(ctx, code, usecase.TelegramUser{ID: 200, Username: username})
		if err != nil {
			t.Fatalf("link %s: %v", username, err)
		}
		if result.Status != domain.AppClientApproved || result.NewRequest {
			t.Fatalf("%s: link result = %+v, want approved without a request", username, result)
		}
		if got := f.status(t, token); got != domain.AppClientApproved {
			t.Fatalf("%s: status = %q", username, got)
		}
	}
}

func TestAppClient_DeniedUserStaysDeniedWithoutNewRequest(t *testing.T) {
	f := newAppClientFixture(t)
	ctx := context.Background()
	_, _ = f.access.CreateRequest(ctx, domain.AccessRequest{TelegramID: 300, Status: domain.AccessStatusDenied})

	token, client := f.register(t)
	code, _, _ := f.svc.IssueLinkCode(ctx, client)
	result, err := f.svc.LinkTelegram(ctx, code, usecase.TelegramUser{ID: 300, Username: "spammer"})
	if err != nil {
		t.Fatalf("link: %v", err)
	}
	if result.Status != domain.AppClientDenied || result.NewRequest {
		t.Fatalf("link result = %+v, want denied without a request", result)
	}
	if got := f.status(t, token); got != domain.AppClientDenied {
		t.Fatalf("status = %q, want denied", got)
	}
}

func TestAppClient_RevokedAccessUnlinksTheClient(t *testing.T) {
	f := newAppClientFixture(t)
	ctx := context.Background()
	token, client := f.register(t)
	f.link(t, client, 400, "former")
	_ = f.access.SetStatus(ctx, 400, domain.AccessStatusApproved)

	// /allowed_users → revoke deletes the access record.
	_ = f.access.DeleteRequest(ctx, 400)
	if got := f.status(t, token); got != domain.AppClientUnlinked {
		t.Fatalf("status = %q, want unlinked", got)
	}
}

func TestAppClient_LinkCodeIsOneTimeAndExpires(t *testing.T) {
	f := newAppClientFixture(t)
	ctx := context.Background()
	_, client := f.register(t)
	code, _, _ := f.svc.IssueLinkCode(ctx, client)

	if _, err := f.svc.LinkTelegram(ctx, "wrong", usecase.TelegramUser{ID: 1, Username: "u"}); !errors.Is(err, usecase.ErrLinkCodeInvalid) {
		t.Fatalf("wrong code: %v", err)
	}

	f.now = f.now.Add(usecase.LinkCodeTTL + time.Second)
	if _, err := f.svc.LinkTelegram(ctx, code, usecase.TelegramUser{ID: 1, Username: "u"}); !errors.Is(err, usecase.ErrLinkCodeInvalid) {
		t.Fatalf("expired code: %v", err)
	}

	code, _, _ = f.svc.IssueLinkCode(ctx, client)
	if _, err := f.svc.LinkTelegram(ctx, code, usecase.TelegramUser{ID: 1, Username: "u"}); err != nil {
		t.Fatalf("fresh code: %v", err)
	}
	if _, err := f.svc.LinkTelegram(ctx, code, usecase.TelegramUser{ID: 2, Username: "other"}); !errors.Is(err, usecase.ErrLinkCodeInvalid) {
		t.Fatalf("reused code: %v", err)
	}
}

func TestAppClient_RegisterPurgesStaleUnlinkedClients(t *testing.T) {
	f := newAppClientFixture(t)
	stale, _ := f.register(t)
	f.now = f.now.Add(usecase.UnlinkedClientTTL + time.Minute)
	f.register(t)
	if _, err := f.svc.Authenticate(context.Background(), stale); !errors.Is(err, usecase.ErrUnauthorized) {
		t.Fatalf("stale unlinked client must be purged, got %v", err)
	}
}

func TestAppClient_CanTunnelOnlyWhenApproved(t *testing.T) {
	f := newAppClientFixture(t)
	ctx := context.Background()
	token, client := f.register(t)
	if ok, _ := f.svc.CanTunnel(ctx, client); ok {
		t.Fatal("unlinked client must not tunnel")
	}
	f.link(t, client, 500, "friend")
	client, _ = f.svc.Authenticate(ctx, token)
	if ok, err := f.svc.CanTunnel(ctx, client); !ok || err != nil {
		t.Fatalf("approved client must tunnel: %v", err)
	}
}
