package storage_test

import (
	"context"
	"database/sql"
	"testing"

	"github.com/lebe-dev/book-recon/internal/adapter/storage"
)

func TestUserRegistryRepo_GetIDByUsername(t *testing.T) {
	db := setupTestDB(t)
	repo := storage.NewUserRegistryRepo(db)
	ctx := context.Background()

	// Register a user
	if err := repo.Register(ctx, 99999, "TestAdmin"); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	// Lookup by lowercase
	id, err := repo.GetIDByUsername(ctx, "testadmin")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if id != 99999 {
		t.Fatalf("expected 99999, got %d", id)
	}

	// Not found
	_, err = repo.GetIDByUsername(ctx, "nonexistent")
	if err != sql.ErrNoRows {
		t.Fatalf("expected sql.ErrNoRows, got %v", err)
	}
}
