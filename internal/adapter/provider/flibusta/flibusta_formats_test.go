package flibusta

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

// authorPageMixedHTML mimics an author page with three books: one converted to
// fb2/epub/mobi, one served as the original pdf upload, and one that can only
// be read online.
const authorPageMixedHTML = `<html><body>
<form action="/a/262121">
- <a href="/b/617927">Высоконагруженные приложения</a> <span style=size>14302K</span> <a href="/b/617927/download">(скачать pdf)</a>
<br>
- <a href="/b/112458">Смерть в «Ла Фениче»</a> <span style=size>471K</span> <a href="/b/112458/read">(читать)</a> скачать: <a href="/b/112458/fb2">(fb2)</a> - <a href="/b/112458/epub">(epub)</a> - <a href="/b/112458/mobi">(mobi)</a>
<br>
- <a href="/b/777777">Только для чтения</a> <a href="/b/777777/read">(читать)</a>
</form>
</body></html>`

func TestParseAuthorBooks_FormatsFromLinks(t *testing.T) {
	entries, err := parseAuthorBooks(strings.NewReader(authorPageMixedHTML), "Автор", 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(entries) != 2 {
		t.Fatalf("expected 2 books, got %d: %+v", len(entries), entries)
	}

	if got := entries[0].formats; len(got) != 1 || got[0] != domain.FormatPDF {
		t.Errorf("formats = %v, want [pdf]", got)
	}

	want := []domain.Format{domain.FormatFB2, domain.FormatEPUB, domain.FormatMOBI}
	got := entries[1].formats
	if len(got) != len(want) {
		t.Fatalf("formats = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("formats[%d] = %q, want %q", i, got[i], want[i])
		}
	}
}

func TestParseAuthorBooks_SkipsReadOnlyBooks(t *testing.T) {
	entries, err := parseAuthorBooks(strings.NewReader(authorPageMixedHTML), "Автор", 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	for _, e := range entries {
		if e.bookURL == "/b/777777" {
			t.Error("book without download links must not be returned")
		}
	}
}

// bookPageHTML mimics a book page whose only download is the original pdf.
const bookPageHTML = `<html><body>
<h1 class="title">Высоконагруженные приложения</h1>
<a href="/b/617927/download">(скачать pdf)</a>
</body></html>`

const searchBooksHTML = `<html><body>
<h3>Найденные книги (1 - 1 из 1):</h3>
<ul><li><a href="/b/617927">Высоконагруженные приложения</a> - <a href="/a/262121">Мартин Клеппман</a></li></ul>
</body></html>`

// TestSearch_FormatsFromBookPage verifies that results coming from the book
// block — which carries no format links — are enriched from the book page.
func TestSearch_FormatsFromBookPage(t *testing.T) {
	var bookPageHits int

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		switch {
		case strings.HasPrefix(r.URL.Path, "/booksearch"):
			_, _ = w.Write([]byte(searchBooksHTML))
		case r.URL.Path == "/b/617927":
			bookPageHits++
			_, _ = w.Write([]byte(bookPageHTML))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())

	results, err := p.Search(context.Background(), "Высоконагруженные приложения", 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(results) != 1 {
		t.Fatalf("expected 1 result, got %d", len(results))
	}
	if bookPageHits != 1 {
		t.Errorf("book page hits = %d, want 1", bookPageHits)
	}

	formats := results[0].Book.Formats
	if len(formats) != 1 || formats[0] != domain.FormatPDF {
		t.Errorf("formats = %v, want [pdf]", formats)
	}
}

// TestSearch_FormatsFallbackOnBookPageFailure keeps the common formats when the
// book page cannot be fetched, so a broken page never hides a result.
func TestSearch_FormatsFallbackOnBookPageFailure(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/booksearch") {
			w.Header().Set("Content-Type", "text/html; charset=utf-8")
			_, _ = w.Write([]byte(searchBooksHTML))
			return
		}
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())

	results, err := p.Search(context.Background(), "Высоконагруженные приложения", 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(results) != 1 {
		t.Fatalf("expected 1 result, got %d", len(results))
	}
	if len(results[0].Book.Formats) != len(defaultFormats) {
		t.Errorf("formats = %v, want %v", results[0].Book.Formats, defaultFormats)
	}
}

// TestSearch_AuthorFallbackKeepsPageFormats ensures author-page results are not
// re-fetched: their formats already come from the page markup.
func TestSearch_AuthorFallbackKeepsPageFormats(t *testing.T) {
	var bookPageHits int

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		switch {
		case strings.HasPrefix(r.URL.Path, "/booksearch"):
			_, _ = w.Write([]byte(`<html><body><h3>Найденные писатели (1 - 1 из 1):</h3>
<ul><li><a href="/a/262121">Мартин Клеппман</a> (1 книга)</li></ul></body></html>`))
		case r.URL.Path == "/a/262121":
			_, _ = w.Write([]byte(authorPageMixedHTML))
		default:
			bookPageHits++
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())

	results, err := p.Search(context.Background(), "Клеппман", 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if bookPageHits != 0 {
		t.Errorf("book pages fetched %d times, want 0", bookPageHits)
	}
	if len(results) != 2 {
		t.Fatalf("expected 2 results, got %d", len(results))
	}
	if formats := results[0].Book.Formats; len(formats) != 1 || formats[0] != domain.FormatPDF {
		t.Errorf("formats = %v, want [pdf]", formats)
	}
}

// TestDownload_PDFUsesDownloadPath: flibusta serves the original upload from
// /download, only fb2/epub/mobi have their own paths.
func TestDownload_PDFUsesDownloadPath(t *testing.T) {
	var gotPath string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		w.Header().Set("Content-Type", "application/pdf")
		_, _ = w.Write([]byte("%PDF-1.4 fake"))
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())
	sr := domain.NewSearchResult(domain.Book{
		Title:     "Высоконагруженные приложения",
		Author:    "Мартин Клеппман",
		Provider:  providerName,
		Formats:   []domain.Format{domain.FormatPDF},
		SourceURL: "/b/617927",
	})

	rc, filename, err := p.Download(context.Background(), sr, domain.FormatPDF)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	defer func() { _ = rc.Close() }()

	if gotPath != "/b/617927/download" {
		t.Errorf("path = %q, want /b/617927/download", gotPath)
	}
	if !strings.HasSuffix(filename, ".pdf") {
		t.Errorf("filename = %q, want a .pdf suffix", filename)
	}
}

// TestDownload_HTMLResponseIsError: flibusta answers a request for a format the
// book does not have with an HTML page instead of a file.
func TestDownload_HTMLResponseIsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write([]byte("<html><body>Книга не найдена</body></html>"))
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())
	sr := domain.NewSearchResult(domain.Book{
		Title:     "Book",
		Author:    "Author",
		Provider:  providerName,
		SourceURL: "/b/617927",
	})

	_, _, err := p.Download(context.Background(), sr, domain.FormatFB2)
	if err == nil {
		t.Fatal("expected an error for an HTML response")
	}

	de, ok := err.(*domain.DomainError)
	if !ok {
		t.Fatalf("expected *domain.DomainError, got %T", err)
	}
	if de.Code != domain.ErrCodeBookUnavailable {
		t.Errorf("code = %q, want %q", de.Code, domain.ErrCodeBookUnavailable)
	}
}
