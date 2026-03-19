package storage_test

import (
	"context"
	"testing"

	"github.com/lebe-dev/book-recon/internal/adapter/storage"
	"github.com/lebe-dev/book-recon/internal/domain"
)

func setupAccessRepo(t *testing.T) *storage.AccessRepo {
	t.Helper()
	db := setupTestDB(t)
	return storage.NewAccessRepo(db)
}

func TestAccessRepo_GetStatus_NoRecord(t *testing.T) {
	repo := setupAccessRepo(t)
	ctx := context.Background()

	status, err := repo.GetStatus(ctx, 12345)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if status != "" {
		t.Fatalf("expected empty status, got %q", status)
	}
}

func TestAccessRepo_CreateRequest(t *testing.T) {
	repo := setupAccessRepo(t)
	ctx := context.Background()

	req := domain.AccessRequest{
		TelegramID: 12345,
		Username:   "testuser",
		FirstName:  "Test",
		Status:     domain.AccessStatusPending,
	}

	created, err := repo.CreateRequest(ctx, req)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !created {
		t.Fatal("expected created=true")
	}

	// Verify status
	status, err := repo.GetStatus(ctx, 12345)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if status != domain.AccessStatusPending {
		t.Fatalf("expected pending, got %q", status)
	}

	// Duplicate insert should return created=false
	created, err = repo.CreateRequest(ctx, req)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if created {
		t.Fatal("expected created=false for duplicate")
	}
}

func TestAccessRepo_SetStatus(t *testing.T) {
	repo := setupAccessRepo(t)
	ctx := context.Background()

	req := domain.AccessRequest{
		TelegramID: 12345,
		Username:   "testuser",
		FirstName:  "Test",
		Status:     domain.AccessStatusPending,
	}
	_, _ = repo.CreateRequest(ctx, req)

	err := repo.SetStatus(ctx, 12345, domain.AccessStatusApproved)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	status, err := repo.GetStatus(ctx, 12345)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if status != domain.AccessStatusApproved {
		t.Fatalf("expected approved, got %q", status)
	}
}

func TestAccessRepo_ListByStatus(t *testing.T) {
	repo := setupAccessRepo(t)
	ctx := context.Background()

	// Create denied and pending requests
	for _, req := range []domain.AccessRequest{
		{TelegramID: 1, Username: "user1", FirstName: "Alice", Status: domain.AccessStatusPending},
		{TelegramID: 2, Username: "user2", FirstName: "Bob", Status: domain.AccessStatusPending},
		{TelegramID: 3, Username: "user3", FirstName: "Carol", Status: domain.AccessStatusPending},
	} {
		_, _ = repo.CreateRequest(ctx, req)
	}
	_ = repo.SetStatus(ctx, 2, domain.AccessStatusDenied)
	_ = repo.SetStatus(ctx, 3, domain.AccessStatusDenied)

	denied, err := repo.ListByStatus(ctx, domain.AccessStatusDenied)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(denied) != 2 {
		t.Fatalf("expected 2 denied, got %d", len(denied))
	}
	for _, req := range denied {
		if req.Status != domain.AccessStatusDenied {
			t.Fatalf("expected denied status, got %q", req.Status)
		}
	}

	// Empty list
	approved, err := repo.ListByStatus(ctx, domain.AccessStatusApproved)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(approved) != 0 {
		t.Fatalf("expected 0 approved, got %d", len(approved))
	}
}

func TestAccessRepo_DeleteRequest(t *testing.T) {
	repo := setupAccessRepo(t)
	ctx := context.Background()

	req := domain.AccessRequest{
		TelegramID: 42,
		Username:   "someone",
		FirstName:  "Some",
		Status:     domain.AccessStatusApproved,
	}
	_, _ = repo.CreateRequest(ctx, req)

	// Verify exists
	status, _ := repo.GetStatus(ctx, 42)
	if status != domain.AccessStatusApproved {
		t.Fatalf("expected approved, got %q", status)
	}

	// Delete
	if err := repo.DeleteRequest(ctx, 42); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	// Verify gone
	status, _ = repo.GetStatus(ctx, 42)
	if status != "" {
		t.Fatalf("expected empty status after delete, got %q", status)
	}
}
