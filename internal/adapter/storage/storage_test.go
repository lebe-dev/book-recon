package storage_test

import (
	"database/sql"
	"testing"

	"github.com/lebe-dev/book-recon/internal/adapter/storage"
)

func setupTestDB(t *testing.T) *sql.DB {
	t.Helper()
	db, err := storage.NewDB(":memory:")
	if err != nil {
		t.Fatalf("failed to create test db: %v", err)
	}
	t.Cleanup(func() { _ = db.Close() })
	return db
}
