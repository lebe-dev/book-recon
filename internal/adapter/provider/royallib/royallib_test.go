package royallib

import (
	"archive/zip"
	"bytes"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

// Minimal search response HTML matching the real site structure.
const fakeSearchHTML = `<html><body>
<h2 class=viewbook>Авторы</h2>
<table><tr><td><a href="//example.com/author/doe_john.html">Doe John</a></td></tr></table>
<h2 class=viewbook>Серии</h2>
<table><tr><td>Не найдено</td></tr></table>
<h2 class=viewbook>Книги</h2>
<table>
<tr><td colspan=3><h2 class=viewbook>Книги</h2></td></tr>
<tr>
  <td><a href="//example.com/book/doe_john/first_book.html">First Book</a></td>
  <td></td>
  <td><a href="//example.com/author/doe_john.html">Doe John</a></td>
</tr>
<tr>
  <td><a href="//example.com/book/smith_jane/second_book.html">Second Book</a></td>
  <td></td>
  <td><a href="//example.com/author/smith_jane.html">Smith Jane</a></td>
</tr>
<tr>
  <td><a href="//example.com/book/smith_jane/third_book.html">Third Book</a></td>
  <td></td>
  <td><a href="//example.com/author/smith_jane.html">Smith Jane</a></td>
</tr>
</table>
</body></html>`

func TestParseSearchResults(t *testing.T) {
	results, err := parseSearchResults(strings.NewReader(fakeSearchHTML), 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(results) != 3 {
		t.Fatalf("expected 3 results, got %d", len(results))
	}

	r := results[0]
	if r.title != "First Book" {
		t.Errorf("title = %q, want %q", r.title, "First Book")
	}
	if r.author != "Doe John" {
		t.Errorf("author = %q, want %q", r.author, "Doe John")
	}
	if r.bookPath != "doe_john/first_book" {
		t.Errorf("bookPath = %q, want %q", r.bookPath, "doe_john/first_book")
	}
}

func TestParseSearchResults_Limit(t *testing.T) {
	results, err := parseSearchResults(strings.NewReader(fakeSearchHTML), 2)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(results) != 2 {
		t.Fatalf("expected 2 results (limit), got %d", len(results))
	}
}

func TestParseSearchResults_NoBooks(t *testing.T) {
	noBooks := `<html><body><h2 class=viewbook>Авторы</h2></body></html>`
	results, err := parseSearchResults(strings.NewReader(noBooks), 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(results) != 0 {
		t.Fatalf("expected 0 results, got %d", len(results))
	}
}

func TestSearch_Integration(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if err := r.ParseForm(); err != nil {
			t.Fatal(err)
		}
		if got := r.FormValue("to"); got != "result" {
			t.Errorf("expected to=result, got %q", got)
		}
		if got := r.FormValue("q"); got != "test query" {
			t.Errorf("expected q='test query', got %q", got)
		}
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = io.WriteString(w, fakeSearchHTML)
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())

	results, err := p.Search(context.Background(), "test query", 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(results) != 3 {
		t.Fatalf("expected 3 results, got %d", len(results))
	}
	if results[0].Book.Title != "First Book" {
		t.Errorf("title = %q", results[0].Book.Title)
	}
	if results[0].Book.Provider != providerName {
		t.Errorf("provider = %q", results[0].Book.Provider)
	}
}

func TestDownload_UnzipsContent(t *testing.T) {
	const innerContent = "fake-epub-content"
	const innerFilename = "Doe John. First Book.epub"

	// Build a real in-memory zip containing one file.
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	fw, err := zw.Create(innerFilename)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = io.WriteString(fw, innerContent); err != nil {
		t.Fatal(err)
	}
	if err = zw.Close(); err != nil {
		t.Fatal(err)
	}
	zipBytes := buf.Bytes()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		wantPath := "/get/epub/doe_john/first_book.zip"
		if r.URL.Path != wantPath {
			t.Errorf("path = %q, want %q", r.URL.Path, wantPath)
		}
		w.Header().Set("Content-Type", "application/zip")
		_, _ = w.Write(zipBytes)
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())

	sr := domain.NewSearchResult(domain.Book{
		Title:     "First Book",
		Author:    "Doe John",
		Provider:  providerName,
		SourceURL: "https://example.com/book/doe_john/first_book.html",
	})

	rc, filename, err := p.Download(context.Background(), sr, domain.FormatEPUB)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	defer func() { _ = rc.Close() }()

	if filename != innerFilename {
		t.Errorf("filename = %q, want %q", filename, innerFilename)
	}

	body, _ := io.ReadAll(rc)
	if string(body) != innerContent {
		t.Errorf("content = %q, want %q", body, innerContent)
	}
}

// TestDownload_EPUBIsZip verifies that when the server returns an EPUB file
// (which is itself a ZIP starting with "mimetype"), the whole buffer is returned
// as-is with an .epub filename instead of extracting the internal "mimetype" entry.
func TestDownload_EPUBIsZip(t *testing.T) {
	// Build a minimal epub-style zip: first entry must be "mimetype".
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	fw, err := zw.CreateHeader(&zip.FileHeader{Name: "mimetype", Method: zip.Store})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = io.WriteString(fw, "application/epub+zip"); err != nil {
		t.Fatal(err)
	}
	if err = zw.Close(); err != nil {
		t.Fatal(err)
	}
	epubBytes := buf.Bytes()

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(epubBytes)
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())
	sr := domain.NewSearchResult(domain.Book{
		Title:     "My Book",
		Author:    "Some Author",
		Provider:  providerName,
		SourceURL: "https://example.com/book/some_author/my_book.html",
	})

	rc, filename, err := p.Download(context.Background(), sr, domain.FormatEPUB)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	defer func() { _ = rc.Close() }()

	if filename != "Some Author. My Book.epub" {
		t.Errorf("filename = %q", filename)
	}

	body, _ := io.ReadAll(rc)
	if !bytes.Equal(body, epubBytes) {
		t.Error("returned body does not match the original epub bytes")
	}
}

// TestDownload_CP1251Filename verifies that zip entry names encoded in CP1251
// are decoded to valid UTF-8.
func TestDownload_CP866Filename(t *testing.T) {
	// "Книга.fb2" in CP866 bytes (DOS OEM Russian).
	// К=0x8A н=0xAD и=0xA8 г=0xA3 а=0xA0
	cp866Name := "\x8a\xad\xa8\xa3\xa0.fb2"

	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	if _, err := zw.CreateHeader(&zip.FileHeader{Name: cp866Name}); err != nil {
		t.Fatal(err)
	}
	if err := zw.Close(); err != nil {
		t.Fatal(err)
	}

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(buf.Bytes())
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())
	sr := domain.NewSearchResult(domain.Book{
		SourceURL: "https://example.com/book/author/book.html",
		Provider:  providerName,
	})

	_, filename, err := p.Download(context.Background(), sr, domain.FormatFB2)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	if filename != "Книга.fb2" {
		t.Errorf("filename = %q, want %q", filename, "Книга.fb2")
	}
}

func TestDownload_EmptyZip(t *testing.T) {
	var buf bytes.Buffer
	if err := zip.NewWriter(&buf).Close(); err != nil {
		t.Fatal(err)
	}

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(buf.Bytes())
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())
	sr := domain.NewSearchResult(domain.Book{
		SourceURL: "https://example.com/book/a/b.html",
		Provider:  providerName,
	})

	_, _, err := p.Download(context.Background(), sr, domain.FormatFB2)
	if err == nil {
		t.Fatal("expected error for empty zip")
	}
}

func TestName(t *testing.T) {
	p := New("", "", log.Default())
	if p.Name() != providerName {
		t.Errorf("Name() = %q", p.Name())
	}
}

func TestExtractBookPath(t *testing.T) {
	tests := []struct {
		url  string
		want string
	}{
		{"https://royallib.com/book/doe_john/first_book.html", "doe_john/first_book"},
		{"//royallib.com/book/smith_jane/second_book.html", "smith_jane/second_book"},
		{"https://royallib.com/author/doe_john.html", ""},
		{"", ""},
	}
	for _, tt := range tests {
		if got := extractBookPath(tt.url); got != tt.want {
			t.Errorf("extractBookPath(%q) = %q, want %q", tt.url, got, tt.want)
		}
	}
}
