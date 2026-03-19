package flibusta

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

const fakeSearchHTML = `<html><body>
<h3>Найденные книги:</h3>
<ul>
<li><a href="/b/435845">Пушкин</a> - <a href="/a/29081">Юрий Тынянов</a></li>
<li><a href="/b/552916">Война и мир [= War and Peace]</a> - <a href="/a/17623">Лев Толстой</a></li>
<li><a href="/b/123456">Идиот</a> - <a href="/a/100">Фёдор Достоевский</a>, <a href="/a/101">Редактор</a></li>
</ul>
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
	if r.title != "Пушкин" {
		t.Errorf("title = %q, want %q", r.title, "Пушкин")
	}
	if r.author != "Юрий Тынянов" {
		t.Errorf("author = %q, want %q", r.author, "Юрий Тынянов")
	}
	if r.bookURL != "/b/435845" {
		t.Errorf("bookURL = %q, want %q", r.bookURL, "/b/435845")
	}

	// Alternative title should be stripped.
	if results[1].title != "Война и мир" {
		t.Errorf("title = %q, want %q", results[1].title, "Война и мир")
	}

	// Multiple authors joined with comma.
	if results[2].author != "Фёдор Достоевский, Редактор" {
		t.Errorf("author = %q, want %q", results[2].author, "Фёдор Достоевский, Редактор")
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
	noBooks := `<html><body><h3>Поиск</h3><p>Ничего не найдено</p></body></html>`
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
		if r.Method != http.MethodGet {
			t.Errorf("expected GET, got %s", r.Method)
		}
		if got := r.URL.Query().Get("ask"); got != "test query" {
			t.Errorf("expected ask='test query', got %q", got)
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
	if results[0].Book.Title != "Пушкин" {
		t.Errorf("title = %q", results[0].Book.Title)
	}
	if results[0].Book.Provider != providerName {
		t.Errorf("provider = %q", results[0].Book.Provider)
	}
}

func TestDownload_ZipWrapped(t *testing.T) {
	const innerContent = "fake-fb2-content"
	const innerFilename = "Author. Title.fb2"

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
		wantPath := "/b/435845/fb2"
		if r.URL.Path != wantPath {
			t.Errorf("path = %q, want %q", r.URL.Path, wantPath)
		}
		w.Header().Set("Content-Type", "application/zip")
		_, _ = w.Write(zipBytes)
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())

	sr := domain.NewSearchResult(domain.Book{
		Title:     "Title",
		Author:    "Author",
		Provider:  providerName,
		SourceURL: "/b/435845",
	})

	rc, filename, err := p.Download(context.Background(), sr, domain.FormatFB2)
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

func TestDownload_EPUBIsZip(t *testing.T) {
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
		SourceURL: "/b/999999",
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

func TestDownload_DirectContent(t *testing.T) {
	const directContent = "plain fb2 content here"

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Disposition", `attachment; filename="Author. Book.fb2"`)
		_, _ = io.WriteString(w, directContent)
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())
	sr := domain.NewSearchResult(domain.Book{
		Title:     "Book",
		Author:    "Author",
		Provider:  providerName,
		SourceURL: "/b/111111",
	})

	rc, filename, err := p.Download(context.Background(), sr, domain.FormatFB2)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	defer func() { _ = rc.Close() }()

	if filename != "Author. Book.fb2" {
		t.Errorf("filename = %q, want %q", filename, "Author. Book.fb2")
	}

	body, _ := io.ReadAll(rc)
	if string(body) != directContent {
		t.Errorf("content = %q, want %q", body, directContent)
	}
}

func TestDownload_DirectContent_FallbackFilename(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "content")
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())
	sr := domain.NewSearchResult(domain.Book{
		Title:     "Book",
		Author:    "Author",
		Provider:  providerName,
		SourceURL: "/b/111111",
	})

	rc, filename, err := p.Download(context.Background(), sr, domain.FormatFB2)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	defer func() { _ = rc.Close() }()

	if filename != "Author. Book.fb2" {
		t.Errorf("filename = %q, want %q", filename, "Author. Book.fb2")
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
		SourceURL: "/b/222222",
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

func TestExtractBookID(t *testing.T) {
	tests := []struct {
		url  string
		want string
	}{
		{"/b/435845", "435845"},
		{"https://flibusta.is/b/435845", "435845"},
		{"/a/29081", ""},
		{"", ""},
	}
	for _, tt := range tests {
		if got := extractBookID(tt.url); got != tt.want {
			t.Errorf("extractBookID(%q) = %q, want %q", tt.url, got, tt.want)
		}
	}
}
