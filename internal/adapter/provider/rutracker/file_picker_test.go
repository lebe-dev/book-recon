package rutracker

import (
	"testing"

	"github.com/lebe-dev/book-recon/internal/domain"
)

func TestPickFiles_PreferredFormat(t *testing.T) {
	files := []DownloadedFile{
		{Path: "/tmp/book1.epub", Name: "book1.epub", Size: 1024},
		{Path: "/tmp/book2.fb2", Name: "book2.fb2", Size: 2048},
		{Path: "/tmp/book3.epub", Name: "book3.epub", Size: 3072},
	}

	picked := PickFiles(files, domain.FormatEPUB, 5)
	if len(picked) != 2 {
		t.Fatalf("expected 2 files, got %d", len(picked))
	}
	for _, p := range picked {
		if p.Format != domain.FormatEPUB {
			t.Errorf("expected epub, got %s", p.Format)
		}
	}
}

func TestPickFiles_FallbackFormat(t *testing.T) {
	files := []DownloadedFile{
		{Path: "/tmp/book1.fb2", Name: "book1.fb2", Size: 1024},
		{Path: "/tmp/book2.pdf", Name: "book2.pdf", Size: 2048},
	}

	// Prefer EPUB but none available — should fallback to FB2 (higher priority than PDF).
	picked := PickFiles(files, domain.FormatEPUB, 5)
	if len(picked) != 1 {
		t.Fatalf("expected 1 file, got %d", len(picked))
	}
	if picked[0].Format != domain.FormatFB2 {
		t.Errorf("expected fb2 fallback, got %s", picked[0].Format)
	}
}

func TestPickFiles_MaxBooks(t *testing.T) {
	files := []DownloadedFile{
		{Path: "/tmp/book1.epub", Name: "book1.epub", Size: 1024},
		{Path: "/tmp/book2.epub", Name: "book2.epub", Size: 2048},
		{Path: "/tmp/book3.epub", Name: "book3.epub", Size: 3072},
	}

	picked := PickFiles(files, domain.FormatEPUB, 2)
	if len(picked) != 2 {
		t.Fatalf("expected 2 files (max), got %d", len(picked))
	}
}

func TestPickFiles_FiltersLargeFiles(t *testing.T) {
	files := []DownloadedFile{
		{Path: "/tmp/small.epub", Name: "small.epub", Size: 1024},
		{Path: "/tmp/huge.epub", Name: "huge.epub", Size: maxTelegramFileSize + 1},
	}

	picked := PickFiles(files, domain.FormatEPUB, 5)
	if len(picked) != 1 {
		t.Fatalf("expected 1 file (filtered large), got %d", len(picked))
	}
	if picked[0].Name != "small.epub" {
		t.Errorf("expected small.epub, got %s", picked[0].Name)
	}
}

func TestPickFiles_IgnoresNonBookFiles(t *testing.T) {
	files := []DownloadedFile{
		{Path: "/tmp/readme.txt", Name: "readme.txt", Size: 100},
		{Path: "/tmp/cover.jpg", Name: "cover.jpg", Size: 200},
		{Path: "/tmp/book.epub", Name: "book.epub", Size: 1024},
	}

	picked := PickFiles(files, domain.FormatEPUB, 5)
	if len(picked) != 1 {
		t.Fatalf("expected 1 file, got %d", len(picked))
	}
	if picked[0].Name != "book.epub" {
		t.Errorf("expected book.epub, got %s", picked[0].Name)
	}
}

func TestPickFiles_Empty(t *testing.T) {
	picked := PickFiles(nil, domain.FormatEPUB, 5)
	if picked != nil {
		t.Errorf("expected nil, got %v", picked)
	}
}
