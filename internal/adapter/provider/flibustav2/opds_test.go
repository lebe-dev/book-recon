package flibustav2

import (
	"context"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// -------------------------------------------------------------------
// Helpers: OPDS XML builders
// -------------------------------------------------------------------

// opdsEntryXML creates a single <entry> element for a fake book.
func opdsEntryXML(bookID int, title, author string, formats []Format) string {
	var sb strings.Builder
	sb.WriteString("<entry>\n")
	sb.WriteString("  <updated>2026-01-01T00:00:00+00:00</updated>\n")
	fmt.Fprintf(&sb, "  <title>%s</title>\n", title)
	fmt.Fprintf(&sb, "  <id>tag:book:%d</id>\n", bookID)
	fmt.Fprintf(&sb, "  <author><name>%s</name><uri>/a/%d</uri></author>\n", author, bookID)
	sb.WriteString("  <category label=\"Fiction\" term=\"fiction\"/>\n")
	sb.WriteString("  <dc:language>ru</dc:language>\n")

	for _, f := range formats {
		mime := formatToMIME(f)
		fmt.Fprintf(&sb, `  <link rel="http://opds-spec.org/acquisition/open-access" type="%s" href="/b/%d/%s"/>`+"\n", mime, bookID, f)
	}
	fmt.Fprintf(&sb, `  <link rel="alternate" type="text/html" href="/b/%d"/>`+"\n", bookID)
	sb.WriteString("</entry>\n")
	return sb.String()
}

func formatToMIME(f Format) string {
	for mime, ff := range mimeToFormat {
		if ff == f {
			return mime
		}
	}
	return "application/octet-stream"
}

// opdsFeedXML wraps entries and optional next link into a full Atom feed.
func opdsFeedXML(entries string, nextHref string) string {
	var sb strings.Builder
	sb.WriteString(`<?xml version="1.0" encoding="utf-8"?>` + "\n")
	sb.WriteString(`<feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/" xmlns:opds="http://opds-spec.org/2010/catalog">` + "\n")
	sb.WriteString("  <id>tag:search</id>\n")
	sb.WriteString("  <title>Search results</title>\n")
	sb.WriteString(`  <link rel="start" href="/opds" type="application/atom+xml;profile=opds-catalog"/>` + "\n")
	if nextHref != "" {
		sb.WriteString(fmt.Sprintf(`  <link rel="next" href="%s" type="application/atom+xml;profile=opds-catalog"/>`, strings.ReplaceAll(nextHref, "&", "&amp;")) + "\n")
	}
	sb.WriteString(entries)
	sb.WriteString("</feed>\n")
	return sb.String()
}

// -------------------------------------------------------------------
// Test server
// -------------------------------------------------------------------

// newTestServer creates an httptest.Server that mimics the Flibusta OPDS API.
func newTestServer(t *testing.T, pages map[int][]testBook) *httptest.Server {
	t.Helper()

	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case strings.HasPrefix(r.URL.Path, "/opds/opensearch"):
			page := 0
			if p := r.URL.Query().Get("pageNumber"); p != "" {
				_, _ = fmt.Sscanf(p, "%d", &page)
			}
			books, ok := pages[page]
			if !ok || len(books) == 0 {
				w.Header().Set("Content-Type", "application/atom+xml")
				_, _ = fmt.Fprint(w, opdsFeedXML("", ""))
				return
			}
			var entriesXML strings.Builder
			for _, b := range books {
				entriesXML.WriteString(opdsEntryXML(b.ID, b.Title, b.Author, b.Formats))
			}
			nextHref := ""
			if _, hasNext := pages[page+1]; hasNext {
				nextHref = fmt.Sprintf("/opds/opensearch?searchType=books&searchTerm=test&pageNumber=%d", page+1)
			}
			w.Header().Set("Content-Type", "application/atom+xml")
			_, _ = fmt.Fprint(w, opdsFeedXML(entriesXML.String(), nextHref))

		case strings.HasPrefix(r.URL.Path, "/b/"):
			parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/b/"), "/")
			if len(parts) != 2 {
				http.NotFound(w, r)
				return
			}
			bookID := parts[0]
			format := parts[1]
			w.Header().Set("Content-Disposition",
				fmt.Sprintf(`attachment; filename="book_%s.%s"`, bookID, format))
			w.Header().Set("Content-Type", "application/octet-stream")
			_, _ = fmt.Fprintf(w, "fake-content-for-%s-%s", bookID, format)

		default:
			http.NotFound(w, r)
		}
	}))
}

type testBook struct {
	ID      int
	Title   string
	Author  string
	Formats []Format
}

// -------------------------------------------------------------------
// Tests: Search
// -------------------------------------------------------------------

func TestSearch_Basic(t *testing.T) {
	srv := newTestServer(t, map[int][]testBook{
		0: {
			{ID: 100, Title: "Book One", Author: "Author A", Formats: []Format{FormatFB2, FormatEPUB}},
			{ID: 200, Title: "Book Two", Author: "Author B", Formats: []Format{FormatFB2, FormatMOBI, FormatPDF}},
		},
	})
	defer srv.Close()

	p := New(WithBaseURL(srv.URL), WithHTTPClient(srv.Client()))
	results, err := p.Search(context.Background(), "test", 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(results) != 2 {
		t.Fatalf("expected 2 results, got %d", len(results))
	}

	r := results[0]
	if r.ID != 100 {
		t.Errorf("expected ID 100, got %d", r.ID)
	}
	if r.Title != "Book One" {
		t.Errorf("expected title 'Book One', got %q", r.Title)
	}
	if len(r.Authors) != 1 || r.Authors[0] != "Author A" {
		t.Errorf("unexpected authors: %v", r.Authors)
	}
	if !r.HasFormat(FormatFB2) || !r.HasFormat(FormatEPUB) {
		t.Errorf("expected formats fb2 and epub, got %v", r.Formats)
	}
	if r.Genre != "Fiction" {
		t.Errorf("expected genre Fiction, got %q", r.Genre)
	}
	if r.Language != "ru" {
		t.Errorf("expected language ru, got %q", r.Language)
	}
}

func TestSearch_Limit(t *testing.T) {
	srv := newTestServer(t, map[int][]testBook{
		0: {
			{ID: 1, Title: "A", Author: "X", Formats: []Format{FormatFB2}},
			{ID: 2, Title: "B", Author: "X", Formats: []Format{FormatFB2}},
			{ID: 3, Title: "C", Author: "X", Formats: []Format{FormatFB2}},
		},
	})
	defer srv.Close()

	p := New(WithBaseURL(srv.URL), WithHTTPClient(srv.Client()))
	results, err := p.Search(context.Background(), "test", 2)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(results) != 2 {
		t.Fatalf("expected 2 results, got %d", len(results))
	}
	if results[0].ID != 1 || results[1].ID != 2 {
		t.Errorf("unexpected IDs: %d, %d", results[0].ID, results[1].ID)
	}
}

func TestSearch_Pagination(t *testing.T) {
	srv := newTestServer(t, map[int][]testBook{
		0: {
			{ID: 1, Title: "Page0-Book1", Author: "X", Formats: []Format{FormatFB2}},
			{ID: 2, Title: "Page0-Book2", Author: "X", Formats: []Format{FormatFB2}},
		},
		1: {
			{ID: 3, Title: "Page1-Book1", Author: "Y", Formats: []Format{FormatEPUB}},
		},
	})
	defer srv.Close()

	p := New(WithBaseURL(srv.URL), WithHTTPClient(srv.Client()))

	results, err := p.Search(context.Background(), "test", 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(results) != 3 {
		t.Fatalf("expected 3 results across 2 pages, got %d", len(results))
	}
	if results[2].Title != "Page1-Book1" {
		t.Errorf("third result should be from page 1, got %q", results[2].Title)
	}
}

func TestSearch_EmptyResult(t *testing.T) {
	srv := newTestServer(t, map[int][]testBook{})
	defer srv.Close()

	p := New(WithBaseURL(srv.URL), WithHTTPClient(srv.Client()))
	results, err := p.Search(context.Background(), "nonexistent", 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(results) != 0 {
		t.Fatalf("expected 0 results, got %d", len(results))
	}
}

func TestSearch_ZeroLimit(t *testing.T) {
	p := New()
	results, err := p.Search(context.Background(), "anything", 0)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if results != nil {
		t.Fatalf("expected nil for limit=0, got %v", results)
	}
}

func TestSearch_ContextCanceled(t *testing.T) {
	srv := newTestServer(t, map[int][]testBook{
		0: {{ID: 1, Title: "A", Author: "X", Formats: []Format{FormatFB2}}},
	})
	defer srv.Close()

	p := New(WithBaseURL(srv.URL), WithHTTPClient(srv.Client()))
	ctx, cancel := context.WithCancel(context.Background())
	cancel() // cancel immediately

	_, err := p.Search(ctx, "test", 10)
	if err == nil {
		t.Fatal("expected error due to canceled context")
	}
}

func TestSearch_ServerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer srv.Close()

	p := New(WithBaseURL(srv.URL), WithHTTPClient(srv.Client()))
	_, err := p.Search(context.Background(), "test", 10)
	if err == nil {
		t.Fatal("expected error on 500 response")
	}
}

func TestSearch_MalformedXML(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/atom+xml")
		_, _ = fmt.Fprint(w, "this is not xml at all <><><>")
	}))
	defer srv.Close()

	p := New(WithBaseURL(srv.URL), WithHTTPClient(srv.Client()))
	_, err := p.Search(context.Background(), "test", 10)
	if err == nil {
		t.Fatal("expected error on malformed XML")
	}
}

// -------------------------------------------------------------------
// Tests: Download
// -------------------------------------------------------------------

func TestDownload_Basic(t *testing.T) {
	srv := newTestServer(t, map[int][]testBook{
		0: {{ID: 42, Title: "Test Book", Author: "A", Formats: []Format{FormatFB2, FormatEPUB}}},
	})
	defer srv.Close()

	p := New(WithBaseURL(srv.URL), WithHTTPClient(srv.Client()))

	sr := SearchResult{
		ID:      42,
		Title:   "Test Book",
		Formats: []Format{FormatFB2, FormatEPUB},
	}

	body, filename, err := p.Download(context.Background(), sr, FormatFB2)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	defer func() { _ = body.Close() }()

	data, _ := io.ReadAll(body)
	if string(data) != "fake-content-for-42-fb2" {
		t.Errorf("unexpected body: %q", string(data))
	}
	if filename != "book_42.fb2" {
		t.Errorf("expected filename 'book_42.fb2', got %q", filename)
	}
}

func TestDownload_EPUB(t *testing.T) {
	srv := newTestServer(t, map[int][]testBook{})
	defer srv.Close()

	p := New(WithBaseURL(srv.URL), WithHTTPClient(srv.Client()))

	sr := SearchResult{
		ID:      99,
		Title:   "Another Book",
		Formats: []Format{FormatEPUB},
	}

	body, filename, err := p.Download(context.Background(), sr, FormatEPUB)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	defer func() { _ = body.Close() }()

	if filename != "book_99.epub" {
		t.Errorf("expected 'book_99.epub', got %q", filename)
	}
}

func TestDownload_UnavailableFormat(t *testing.T) {
	p := New()

	sr := SearchResult{
		ID:      1,
		Title:   "X",
		Formats: []Format{FormatFB2},
	}

	_, _, err := p.Download(context.Background(), sr, FormatPDF)
	if err == nil {
		t.Fatal("expected error when downloading unavailable format")
	}
	if !strings.Contains(err.Error(), "not available") {
		t.Errorf("error should mention format unavailability, got: %v", err)
	}
}

func TestDownload_ZeroID(t *testing.T) {
	p := New()

	sr := SearchResult{ID: 0, Title: "X", Formats: []Format{FormatFB2}}
	_, _, err := p.Download(context.Background(), sr, FormatFB2)
	if err == nil {
		t.Fatal("expected error for zero book ID")
	}
}

func TestDownload_ServerError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
	}))
	defer srv.Close()

	p := New(WithBaseURL(srv.URL), WithHTTPClient(srv.Client()))
	sr := SearchResult{ID: 999, Title: "X", Formats: []Format{FormatFB2}}
	_, _, err := p.Download(context.Background(), sr, FormatFB2)
	if err == nil {
		t.Fatal("expected error on 404")
	}
	if !strings.Contains(err.Error(), "404") {
		t.Errorf("error should mention 404, got: %v", err)
	}
}

func TestDownload_ContextCanceled(t *testing.T) {
	srv := newTestServer(t, map[int][]testBook{})
	defer srv.Close()

	p := New(WithBaseURL(srv.URL), WithHTTPClient(srv.Client()))
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	sr := SearchResult{ID: 1, Title: "X", Formats: []Format{FormatFB2}}
	_, _, err := p.Download(ctx, sr, FormatFB2)
	if err == nil {
		t.Fatal("expected error for canceled context")
	}
}

func TestDownload_ContentDispositionFilename(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Disposition", `attachment; filename="custom_name.fb2.zip"`)
		_, _ = fmt.Fprint(w, "data")
	}))
	defer srv.Close()

	p := New(WithBaseURL(srv.URL), WithHTTPClient(srv.Client()))
	sr := SearchResult{ID: 1, Title: "Ignored", Formats: []Format{FormatFB2}}

	body, filename, err := p.Download(context.Background(), sr, FormatFB2)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	defer func() { _ = body.Close() }()

	if filename != "custom_name.fb2.zip" {
		t.Errorf("expected 'custom_name.fb2.zip', got %q", filename)
	}
}

func TestDownload_FallbackFilename(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// No Content-Disposition header.
		_, _ = fmt.Fprint(w, "data")
	}))
	defer srv.Close()

	p := New(WithBaseURL(srv.URL), WithHTTPClient(srv.Client()))
	sr := SearchResult{ID: 5, Title: "My Great Book!", Formats: []Format{FormatEPUB}}

	body, filename, err := p.Download(context.Background(), sr, FormatEPUB)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	defer func() { _ = body.Close() }()

	if filename != "My Great Book!.epub" {
		t.Errorf("expected 'My Great Book!.epub', got %q", filename)
	}
}

func TestDownload_FallbackFilenameEmptyTitle(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = fmt.Fprint(w, "data")
	}))
	defer srv.Close()

	p := New(WithBaseURL(srv.URL), WithHTTPClient(srv.Client()))
	sr := SearchResult{ID: 7, Title: "", Formats: []Format{FormatFB2}}

	body, filename, err := p.Download(context.Background(), sr, FormatFB2)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	defer func() { _ = body.Close() }()

	if filename != "book_7.fb2.zip" {
		t.Errorf("expected 'book_7.fb2.zip', got %q", filename)
	}
}

// -------------------------------------------------------------------
// Tests: Name
// -------------------------------------------------------------------

func TestName(t *testing.T) {
	p := New()
	if p.Name() != "Flibusta" {
		t.Errorf("expected name 'Flibusta', got %q", p.Name())
	}
}

// -------------------------------------------------------------------
// Tests: HasFormat
// -------------------------------------------------------------------

func TestHasFormat(t *testing.T) {
	sr := SearchResult{Formats: []Format{FormatFB2, FormatEPUB}}
	if !sr.HasFormat(FormatFB2) {
		t.Error("should have fb2")
	}
	if !sr.HasFormat(FormatEPUB) {
		t.Error("should have epub")
	}
	if sr.HasFormat(FormatPDF) {
		t.Error("should not have pdf")
	}
}

// -------------------------------------------------------------------
// Tests: sanitizeFilename
// -------------------------------------------------------------------

func TestSanitizeFilename(t *testing.T) {
	tests := []struct {
		in, want string
	}{
		{"normal", "normal"},
		{"a/b\\c:d", "a_b_c_d"},
		{`a*b?c"d<e>f|g`, "a_b_c_d_e_f_g"},
		{"  spaces  ", "spaces"},
		{"Мастер и Маргарита", "Мастер и Маргарита"},
	}
	for _, tt := range tests {
		got := sanitizeFilename(tt.in)
		if got != tt.want {
			t.Errorf("sanitizeFilename(%q) = %q, want %q", tt.in, got, tt.want)
		}
	}
}

// -------------------------------------------------------------------
// Tests: Interface compliance
// -------------------------------------------------------------------

func TestProviderImplementsBookProvider(t *testing.T) {
	var _ BookProvider = (*Provider)(nil)
}
