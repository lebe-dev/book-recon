package usecase

import (
	"context"
	"database/sql"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

type AccessService struct {
	accessRepo domain.AccessRepository
	userRepo   domain.UserRepository
	logger     *log.Logger
}

func NewAccessService(accessRepo domain.AccessRepository, userRepo domain.UserRepository, logger *log.Logger) *AccessService {
	return &AccessService{
		accessRepo: accessRepo,
		userRepo:   userRepo,
		logger:     logger,
	}
}

func (s *AccessService) CheckAccess(ctx context.Context, telegramID int64) (domain.AccessStatus, error) {
	return s.accessRepo.GetStatus(ctx, telegramID)
}

func (s *AccessService) RequestAccess(ctx context.Context, req domain.AccessRequest) (created bool, err error) {
	req.Status = domain.AccessStatusPending
	return s.accessRepo.CreateRequest(ctx, req)
}

func (s *AccessService) ApproveUser(ctx context.Context, telegramID int64) error {
	return s.accessRepo.SetStatus(ctx, telegramID, domain.AccessStatusApproved)
}

func (s *AccessService) DenyUser(ctx context.Context, telegramID int64) error {
	return s.accessRepo.SetStatus(ctx, telegramID, domain.AccessStatusDenied)
}

func (s *AccessService) ListDeniedUsers(ctx context.Context) ([]domain.AccessRequest, error) {
	return s.accessRepo.ListByStatus(ctx, domain.AccessStatusDenied)
}

func (s *AccessService) ListApprovedUsers(ctx context.Context) ([]domain.AccessRequest, error) {
	return s.accessRepo.ListByStatus(ctx, domain.AccessStatusApproved)
}

func (s *AccessService) RevokeUser(ctx context.Context, telegramID int64) error {
	return s.accessRepo.DeleteRequest(ctx, telegramID)
}

// ResolveAdminIDs looks up telegram IDs for admin usernames via the users table.
// Admins must have interacted with the bot at least once (/start) for their ID to be known.
func (s *AccessService) ResolveAdminIDs(ctx context.Context, adminUsernames []string) []int64 {
	var ids []int64
	for _, username := range adminUsernames {
		id, err := s.userRepo.GetIDByUsername(ctx, username)
		if err != nil {
			if err != sql.ErrNoRows {
				s.logger.Warn("failed to resolve admin ID", "username", username, "error", err)
			}
			continue
		}
		ids = append(ids, id)
	}
	return ids
}
