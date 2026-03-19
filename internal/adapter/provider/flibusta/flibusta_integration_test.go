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
	integrationBookID     = "435845"
)

func TestIntegration_Search(t *testing.T) {
	p := New("", log.Default())
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	results, err := p.Search(ctx, "Пушкин Тынянов", 10)
	if err != nil {
		t.Fatalf("search failed: %v", err)
	}
	if len(results) == 0 {
		t.Fatal("expected at least one result, got 0")
	}

	var found *domain.SearchResult
	for i := range results {
		if extractBookID(results[i].Book.SourceURL) == integrationBookID {
			found = &results[i]
			break
		}
	}
	if found == nil {
		t.Fatalf("book %q not found in results; got: %v", integrationBookID, results)
	}

	t.Logf("found: %q by %q (id=%s)", found.Book.Title, found.Book.Author, found.ID)
}

func TestIntegration_Download(t *testing.T) {
	p := New("", log.Default())
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
