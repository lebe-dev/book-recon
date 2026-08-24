package flibusta

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/charmbracelet/log"
)

// searchAuthorsHTML mimics a flibusta search page that matched an author but no books.
const searchAuthorsHTML = `<html><body>
<h3> Найденные писатели (1 - 2 из 2):</h3>
<ul>
<li><a href="/a/151853"><b>Шинзен</b> <b>Янг</b></a> (1 книга)</li>
<li><a href="/a/999"><b>Другой</b> Автор</a> (3 книги)</li>
</ul>
</body></html>`

// authorPageHTML mimics an author page: own books carry download links,
// while the comments block links books that are not the author's.
const authorPageHTML = `<html><body>
<h1 class="title">Шинзен Янг</h1>
<form action="/a/151853">
<a href="/b/411780">Естественное избавление от боли</a> (пер. <a href="/a/151854">Виктор Ширяев</a>)
<span style=size>2251K</span> <a href="/b/411780/read">(читать)</a> скачать:
<a href="/b/411780/fb2">(fb2)</a> - <a href="/b/411780/epub">(epub)</a>
<a href="/b/222222">Вторая книга</a> <a href="/b/222222/fb2">(fb2)</a>
</form>
<div class="container_884328">RADIANT_15 про <a href="/a/10677">Садов</a>: <a href="/b/884328">Война 1</a></div>
</body></html>`

func TestParseSearchPage_Authors(t *testing.T) {
	books, authors, err := parseSearchPage(strings.NewReader(searchAuthorsHTML), 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(books) != 0 {
		t.Fatalf("expected 0 books, got %d", len(books))
	}
	if len(authors) != 2 {
		t.Fatalf("expected 2 authors, got %d", len(authors))
	}
	if authors[0].name != "Шинзен Янг" {
		t.Errorf("name = %q, want %q", authors[0].name, "Шинзен Янг")
	}
	if authors[0].url != "/a/151853" {
		t.Errorf("url = %q, want %q", authors[0].url, "/a/151853")
	}
}

func TestParseSearchPage_BooksTakePrecedence(t *testing.T) {
	books, authors, err := parseSearchPage(strings.NewReader(fakeSearchHTML), 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(books) != 3 {
		t.Fatalf("expected 3 books, got %d", len(books))
	}
	if len(authors) != 0 {
		t.Fatalf("expected 0 authors, got %d", len(authors))
	}
}

func TestParseAuthorBooks(t *testing.T) {
	entries, err := parseAuthorBooks(strings.NewReader(authorPageHTML), "Шинзен Янг", 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(entries) != 2 {
		t.Fatalf("expected 2 books, got %d: %+v", len(entries), entries)
	}

	first := entries[0]
	if first.title != "Естественное избавление от боли" {
		t.Errorf("title = %q", first.title)
	}
	if first.bookURL != "/b/411780" {
		t.Errorf("bookURL = %q, want /b/411780", first.bookURL)
	}
	if first.author != "Шинзен Янг" {
		t.Errorf("author = %q, want %q", first.author, "Шинзен Янг")
	}

	// The book referenced from the comments block has no download links and must be skipped.
	for _, e := range entries {
		if e.bookURL == "/b/884328" {
			t.Errorf("comment-block book /b/884328 must not be returned")
		}
	}
}

func TestParseAuthorBooks_Limit(t *testing.T) {
	entries, err := parseAuthorBooks(strings.NewReader(authorPageHTML), "Шинзен Янг", 1)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("expected 1 book (limit), got %d", len(entries))
	}
}

// TestSearch_AuthorFallback verifies that a query matching only an author
// falls back to fetching that author's page and returns their books.
func TestSearch_AuthorFallback(t *testing.T) {
	var authorPageHits int

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		switch {
		case strings.HasPrefix(r.URL.Path, "/booksearch"):
			_, _ = w.Write([]byte(searchAuthorsHTML))
		case r.URL.Path == "/a/151853":
			authorPageHits++
			_, _ = w.Write([]byte(authorPageHTML))
		case r.URL.Path == "/a/999":
			authorPageHits++
			_, _ = w.Write([]byte(`<html><body><h1 class="title">Другой Автор</h1></body></html>`))
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())

	results, err := p.Search(context.Background(), "Янг Шинзен", 10)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(results) != 2 {
		t.Fatalf("expected 2 results from author fallback, got %d", len(results))
	}
	if results[0].Book.Author != "Шинзен Янг" {
		t.Errorf("author = %q, want %q", results[0].Book.Author, "Шинзен Янг")
	}
	if results[0].Book.SourceURL != "/b/411780" {
		t.Errorf("SourceURL = %q, want /b/411780", results[0].Book.SourceURL)
	}
	if authorPageHits == 0 {
		t.Error("author page was never fetched")
	}
}

// TestSearch_AuthorFallbackRespectsLimit ensures the fallback never exceeds the requested limit.
func TestSearch_AuthorFallbackRespectsLimit(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		if strings.HasPrefix(r.URL.Path, "/booksearch") {
			_, _ = w.Write([]byte(searchAuthorsHTML))
			return
		}
		_, _ = w.Write([]byte(authorPageHTML))
	}))
	defer srv.Close()

	p := newWithBaseURL(srv.URL, srv.Client(), log.Default())

	results, err := p.Search(context.Background(), "Янг Шинзен", 1)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(results) != 1 {
		t.Fatalf("expected 1 result (limit), got %d", len(results))
	}
}
