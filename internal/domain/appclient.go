package domain

import (
	"context"
	"time"
)

// AppClient is an installation of the Android app registered on this server. It authenticates
// with a bearer token, of which only the SHA-256 hash is stored. A client is linked to a
// Telegram account through a one-time code the user sends to the bot; access is then decided
// by the same approval flow as the bot's own users.
type AppClient struct {
	ID         string
	TokenHash  string
	DeviceName string
	// TelegramID is zero until the client is linked.
	TelegramID int64
	Username   string
	// LinkCode is empty when no code is pending.
	LinkCode      string
	LinkExpiresAt time.Time
	CreatedAt     time.Time
	LastSeenAt    time.Time
}

// Linked reports whether the client is bound to a Telegram account.
func (c *AppClient) Linked() bool { return c.TelegramID != 0 }

// AppClientStatus is what the app shows about its connection to the server.
type AppClientStatus string

const (
	// AppClientUnlinked means the client has no Telegram account yet.
	AppClientUnlinked AppClientStatus = "unlinked"
	AppClientPending  AppClientStatus = "pending"
	AppClientApproved AppClientStatus = "approved"
	AppClientDenied   AppClientStatus = "denied"
)

// AppClientRepository stores app clients. Getters return nil and no error when nothing matches.
type AppClientRepository interface {
	Create(ctx context.Context, client AppClient) error
	GetByTokenHash(ctx context.Context, tokenHash string) (*AppClient, error)
	GetByLinkCode(ctx context.Context, code string) (*AppClient, error)
	SetLinkCode(ctx context.Context, id, code string, expiresAt time.Time) error
	// Link binds the client to a Telegram account and clears its link code.
	Link(ctx context.Context, id string, telegramID int64, username string) error
	// Unlink removes the Telegram account from the client.
	Unlink(ctx context.Context, id string) error
	Delete(ctx context.Context, id string) error
	Touch(ctx context.Context, id string, at time.Time) error
	// DeleteUnlinkedBefore removes clients that were never linked and were created before t.
	DeleteUnlinkedBefore(ctx context.Context, t time.Time) (int64, error)
	// CountLinked returns the number of linked clients per Telegram ID.
	CountLinked(ctx context.Context) (map[int64]int, error)
}
