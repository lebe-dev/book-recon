package storage_test

import (
	"context"
	"testing"
	"time"

	"github.com/lebe-dev/book-recon/internal/adapter/storage"
	"github.com/lebe-dev/book-recon/internal/domain"
)

func setupAppClientRepo(t *testing.T) *storage.AppClientRepo {
	t.Helper()
	return storage.NewAppClientRepo(setupTestDB(t))
}

func TestAppClientRepo_CreateAndGet(t *testing.T) {
	repo := setupAppClientRepo(t)
	ctx := context.Background()
	now := time.Date(2026, 11, 10, 9, 0, 0, 0, time.UTC)

	err := repo.Create(ctx, domain.AppClient{ID: "c1", TokenHash: "h1", DeviceName: "Pixel", CreatedAt: now, LastSeenAt: now})
	if err != nil {
		t.Fatalf("create: %v", err)
	}

	got, err := repo.GetByTokenHash(ctx, "h1")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if got == nil || got.ID != "c1" || got.DeviceName != "Pixel" || got.Linked() || !got.CreatedAt.Equal(now) {
		t.Fatalf("unexpected client: %+v", got)
	}

	missing, err := repo.GetByTokenHash(ctx, "nope")
	if err != nil || missing != nil {
		t.Fatalf("expected nil for unknown hash, got %+v, %v", missing, err)
	}
}

func TestAppClientRepo_LinkFlow(t *testing.T) {
	repo := setupAppClientRepo(t)
	ctx := context.Background()
	now := time.Date(2026, 11, 10, 9, 0, 0, 0, time.UTC)
	_ = repo.Create(ctx, domain.AppClient{ID: "c1", TokenHash: "h1", CreatedAt: now, LastSeenAt: now})

	if err := repo.SetLinkCode(ctx, "c1", "code1", now.Add(15*time.Minute)); err != nil {
		t.Fatalf("set code: %v", err)
	}
	byCode, err := repo.GetByLinkCode(ctx, "code1")
	if err != nil || byCode == nil || byCode.ID != "c1" || !byCode.LinkExpiresAt.Equal(now.Add(15*time.Minute)) {
		t.Fatalf("get by code: %+v, %v", byCode, err)
	}

	if err := repo.Link(ctx, "c1", 42, "reader"); err != nil {
		t.Fatalf("link: %v", err)
	}
	linked, _ := repo.GetByTokenHash(ctx, "h1")
	if linked.TelegramID != 42 || linked.Username != "reader" || linked.LinkCode != "" {
		t.Fatalf("unexpected linked client: %+v", linked)
	}
	if again, _ := repo.GetByLinkCode(ctx, "code1"); again != nil {
		t.Fatal("link code must be cleared after linking")
	}

	counts, err := repo.CountLinked(ctx)
	if err != nil || counts[42] != 1 {
		t.Fatalf("count linked: %v, %v", counts, err)
	}

	if err := repo.Unlink(ctx, "c1"); err != nil {
		t.Fatalf("unlink: %v", err)
	}
	unlinked, _ := repo.GetByTokenHash(ctx, "h1")
	if unlinked.Linked() || unlinked.Username != "" {
		t.Fatalf("expected unlinked client: %+v", unlinked)
	}
}

func TestAppClientRepo_DeleteUnlinkedBefore(t *testing.T) {
	repo := setupAppClientRepo(t)
	ctx := context.Background()
	old := time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC)
	fresh := time.Date(2026, 11, 10, 0, 0, 0, 0, time.UTC)
	_ = repo.Create(ctx, domain.AppClient{ID: "old-unlinked", TokenHash: "h1", CreatedAt: old, LastSeenAt: old})
	_ = repo.Create(ctx, domain.AppClient{ID: "old-linked", TokenHash: "h2", CreatedAt: old, LastSeenAt: old})
	_ = repo.Link(ctx, "old-linked", 7, "u")
	_ = repo.Create(ctx, domain.AppClient{ID: "fresh", TokenHash: "h3", CreatedAt: fresh, LastSeenAt: fresh})

	n, err := repo.DeleteUnlinkedBefore(ctx, time.Date(2026, 11, 5, 0, 0, 0, 0, time.UTC))
	if err != nil || n != 1 {
		t.Fatalf("expected 1 deleted, got %d, %v", n, err)
	}
	for hash, want := range map[string]bool{"h1": false, "h2": true, "h3": true} {
		got, _ := repo.GetByTokenHash(ctx, hash)
		if (got != nil) != want {
			t.Fatalf("client %s present=%v, want %v", hash, got != nil, want)
		}
	}
}

func TestAppClientRepo_TouchAndDelete(t *testing.T) {
	repo := setupAppClientRepo(t)
	ctx := context.Background()
	now := time.Date(2026, 11, 10, 9, 0, 0, 0, time.UTC)
	_ = repo.Create(ctx, domain.AppClient{ID: "c1", TokenHash: "h1", CreatedAt: now, LastSeenAt: now})

	later := now.Add(time.Hour)
	if err := repo.Touch(ctx, "c1", later); err != nil {
		t.Fatalf("touch: %v", err)
	}
	got, _ := repo.GetByTokenHash(ctx, "h1")
	if !got.LastSeenAt.Equal(later) {
		t.Fatalf("last seen = %v, want %v", got.LastSeenAt, later)
	}

	if err := repo.Delete(ctx, "c1"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	if got, _ := repo.GetByTokenHash(ctx, "h1"); got != nil {
		t.Fatal("client must be gone after delete")
	}
}
