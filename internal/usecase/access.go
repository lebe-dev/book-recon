package usecase

import (
	"context"
	"database/sql"
	"slices"
	"strings"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

type AccessService struct {
	accessRepo   domain.AccessRepository
	userRepo     domain.UserRepository
	allowedUsers []string
	adminUsers   []string
	logger       *log.Logger
}

// NewAccessService takes the static ALLOWED_USERS and ADMIN_USERS lists as lowercase
// usernames without '@'.
func NewAccessService(accessRepo domain.AccessRepository, userRepo domain.UserRepository, allowedUsers, adminUsers []string, logger *log.Logger) *AccessService {
	return &AccessService{
		accessRepo:   accessRepo,
		userRepo:     userRepo,
		allowedUsers: allowedUsers,
		adminUsers:   adminUsers,
		logger:       logger,
	}
}

// EffectiveStatus decides access the way the bot does: the static allow and admin lists
// first, then the stored decision. An empty status means nobody has decided yet.
func (s *AccessService) EffectiveStatus(ctx context.Context, telegramID int64, username string) (domain.AccessStatus, error) {
	if s.IsStaticallyAllowed(username) {
		return domain.AccessStatusApproved, nil
	}
	return s.accessRepo.GetStatus(ctx, telegramID)
}

// IsStaticallyAllowed reports whether the username is in ALLOWED_USERS or ADMIN_USERS.
func (s *AccessService) IsStaticallyAllowed(username string) bool {
	username = strings.ToLower(username)
	if username == "" {
		return false
	}
	return slices.Contains(s.allowedUsers, username) || slices.Contains(s.adminUsers, username)
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
