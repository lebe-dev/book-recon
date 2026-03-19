package domain

import "context"

type AccessStatus string

const (
	AccessStatusPending  AccessStatus = "pending"
	AccessStatusApproved AccessStatus = "approved"
	AccessStatusDenied   AccessStatus = "denied"
)

type AccessRequest struct {
	TelegramID int64
	Username   string
	FirstName  string
	Status     AccessStatus
}

type AccessRepository interface {
	GetStatus(ctx context.Context, telegramID int64) (AccessStatus, error)
	CreateRequest(ctx context.Context, req AccessRequest) (created bool, err error)
	SetStatus(ctx context.Context, telegramID int64, status AccessStatus) error
	DeleteRequest(ctx context.Context, telegramID int64) error
	ListByStatus(ctx context.Context, status AccessStatus) ([]AccessRequest, error)
}
