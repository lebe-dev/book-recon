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

func TestUserRegistryRepo_ListAllIDs(t *testing.T) {
	db := setupTestDB(t)
	repo := storage.NewUserRegistryRepo(db)
	ctx := context.Background()

	// Empty table
	ids, err := repo.ListAllIDs(ctx)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(ids) != 0 {
		t.Fatalf("expected empty list, got %d", len(ids))
	}

	// Register users
	for _, u := range []struct {
		id       int64
		username string
	}{
		{100, "alice"},
		{200, "bob"},
		{300, "charlie"},
	} {
		if err := repo.Register(ctx, u.id, u.username); err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
	}

	ids, err = repo.ListAllIDs(ctx)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(ids) != 3 {
		t.Fatalf("expected 3 IDs, got %d", len(ids))
	}

	got := map[int64]bool{}
	for _, id := range ids {
		got[id] = true
	}
	for _, expected := range []int64{100, 200, 300} {
		if !got[expected] {
			t.Fatalf("expected ID %d in result", expected)
		}
	}
}
