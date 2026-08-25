//go:build integration

package flibusta

import (
	"context"
	"io"
	"testing"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

const (
	integrationBookURL    = "/b/435845"
	integrationBookAuthor = "Юрий Тынянов"
	integrationBookTitle  = "Пушкин"
)

func TestIntegration_Search(t *testing.T) {
	p := New("", "", log.Default())
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	// Flibusta matches the query as a substring, so a two-word query like
	// "Пушкин Тынянов" finds nothing.
	results, err := p.Search(ctx, "Тынянов", 10)
	if err != nil {
		t.Fatalf("search failed: %v", err)
	}
	if len(results) == 0 {
		t.Fatal("expected at least one result, got 0")
	}

	for _, r := range results {
		if len(r.Book.Formats) == 0 {
			t.Errorf("book %q has no formats", r.Book.Title)
		}
		t.Logf("found: %q by %q formats=%v", r.Book.Title, r.Book.Author, r.Book.Formats)
	}
}

func TestIntegration_Download(t *testing.T) {
	p := New("", "", log.Default())
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	sr := domain.NewSearchResult(domain.Book{
		Title:     integrationBookTitle,
		Author:    integrationBookAuthor,
		Provider:  providerName,
		SourceURL: integrationBookURL,
	})

	for _, format := range []domain.Format{domain.FormatFB2, domain.FormatEPUB} {
		t.Run(string(format), func(t *testing.T) {
			rc, filename, err := p.Download(ctx, sr, format)
			if err != nil {
				t.Fatalf("download failed: %v", err)
			}
			defer func() { _ = rc.Close() }()

			n, err := io.Copy(io.Discard, rc)
			if err != nil {
				t.Fatalf("read failed: %v", err)
			}
			if n == 0 {
				t.Fatal("downloaded file is empty")
			}

			t.Logf("format=%s filename=%q size=%d bytes", format, filename, n)
		})
	}
}

// TestIntegration_KleppmanPDF covers a book whose only download is the original
// pdf upload: the search must report pdf and the download must succeed.
func TestIntegration_KleppmanPDF(t *testing.T) {
	p := New("", "", log.Default())
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	results, err := p.Search(ctx, "Высоконагруженные приложения", 5)
	if err != nil {
		t.Fatalf("search failed: %v", err)
	}
	if len(results) == 0 {
		t.Fatal("no results")
	}

	book := results[0]
	if !book.Book.HasFormat(domain.FormatPDF) {
		t.Fatalf("formats = %v, want pdf among them", book.Book.Formats)
	}
	if book.Book.HasFormat(domain.FormatFB2) {
		t.Errorf("formats = %v, must not offer fb2", book.Book.Formats)
	}

	rc, filename, err := p.Download(ctx, book, domain.FormatPDF)
	if err != nil {
		t.Fatalf("download failed: %v", err)
	}
	defer func() { _ = rc.Close() }()

	head := make([]byte, 4)
	if _, err := io.ReadFull(rc, head); err != nil {
		t.Fatalf("read failed: %v", err)
	}
	if string(head) != "%PDF" {
		t.Errorf("downloaded %q, header = %q, want %%PDF", filename, head)
	}
}
