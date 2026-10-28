package storage_test

import (
	"context"
	"testing"
	"time"

	"github.com/lebe-dev/book-recon/internal/adapter/storage"
	"github.com/lebe-dev/book-recon/internal/domain"
)

func TestAudiobookDownloadRepo_SaveListDelete(t *testing.T) {
	db := setupTestDB(t)
	repo := storage.NewAudiobookDownloadRepo(db)
	ctx := context.Background()

	created := time.Date(2026, 1, 2, 3, 4, 5, 0, time.UTC)
	d := &domain.AudiobookDownload{
		ID:              "abc",
		TelegramID:      1,
		ChatID:          2,
		StatusMessageID: 3,
		Title:           "Book",
		Status:          domain.AudiobookJobDownloading,
		CreatedAt:       created,
	}
	if err := repo.Save(ctx, d); err != nil {
		t.Fatalf("Save: %v", err)
	}

	// Upsert to completed.
	d.Status = domain.AudiobookJobCompleted
	d.Files = []domain.StoredFile{{Path: "cd1/01.mp3", Size: 10}}
	d.Size = 10
	d.ExpiresAt = created.Add(24 * time.Hour)
	if err := repo.Save(ctx, d); err != nil {
		t.Fatalf("Save (update): %v", err)
	}

	list, err := repo.List(ctx)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if len(list) != 1 {
		t.Fatalf("List len = %d, want 1", len(list))
	}
	got := list[0]
	if got.Status != domain.AudiobookJobCompleted || got.Size != 10 || got.StatusMessageID != 3 {
		t.Errorf("unexpected record: %+v", got)
	}
	if len(got.Files) != 1 || got.Files[0].Path != "cd1/01.mp3" {
		t.Errorf("Files = %+v", got.Files)
	}
	if !got.CreatedAt.Equal(created) || !got.ExpiresAt.Equal(d.ExpiresAt) {
		t.Errorf("times = %v / %v", got.CreatedAt, got.ExpiresAt)
	}

	if err := repo.Delete(ctx, "abc"); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	list, _ = repo.List(ctx)
	if len(list) != 0 {
		t.Errorf("List after delete len = %d", len(list))
	}
}

func TestAppSettingsRepo(t *testing.T) {
	db := setupTestDB(t)
	repo := storage.NewAppSettingsRepo(db)
	ctx := context.Background()

	_, found, err := repo.GetBool(ctx, "k")
	if err != nil || found {
		t.Fatalf("GetBool on missing key: found=%v err=%v", found, err)
	}

	for _, v := range []bool{false, true} {
		if err := repo.SetBool(ctx, "k", v); err != nil {
			t.Fatalf("SetBool: %v", err)
		}
		got, found, err := repo.GetBool(ctx, "k")
		if err != nil || !found || got != v {
			t.Errorf("GetBool = %v, %v, %v; want %v", got, found, err, v)
		}
	}
}
