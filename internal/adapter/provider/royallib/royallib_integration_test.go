//go:build integration

package royallib

import (
	"context"
	"io"
	"testing"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

// Real book: https://royallib.com/book/maligina_irina/polkovnik_i_rogdestvo.html
const (
	integrationBookURL    = "https://royallib.com/book/maligina_irina/polkovnik_i_rogdestvo.html"
	integrationBookAuthor = "Малигина Ирина"
	integrationBookTitle  = "Полковник и Рождество"
	integrationBookPath   = "maligina_irina/polkovnik_i_rogdestvo"
)

func TestIntegration_Search(t *testing.T) {
	p := New("", "", log.Default())
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	results, err := p.Search(ctx, "Полковник и Рождество Малигина", 10)
	if err != nil {
		t.Fatalf("search failed: %v", err)
	}
	if len(results) == 0 {
		t.Fatal("expected at least one result, got 0")
	}

	var found *domain.SearchResult
	for i := range results {
		if extractBookPath(results[i].Book.SourceURL) == integrationBookPath {
			found = &results[i]
			break
		}
	}
	if found == nil {
		t.Fatalf("book %q not found in results; got: %v", integrationBookPath, results)
	}

	t.Logf("found: %q by %q (id=%s)", found.Book.Title, found.Book.Author, found.ID)
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
