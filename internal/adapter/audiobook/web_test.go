package audiobook

import (
	"archive/zip"
	"bytes"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

type mapLookup map[string]*domain.AudiobookDownload

func (m mapLookup) GetDownload(id string) (*domain.AudiobookDownload, bool) {
	d, ok := m[id]
	return d, ok
}

const testID = "0123456789abcdef0123456789abcdef"

func newTestWeb(t *testing.T) (*WebServer, string) {
	t.Helper()
	dir := t.TempDir()
	files := map[string]string{"cd1/01.mp3": "first", "cd1/02.mp3": "second-file"}
	var stored []domain.StoredFile
	for _, name := range []string{"cd1/01.mp3", "cd1/02.mp3"} {
		p := filepath.Join(dir, testID, filepath.FromSlash(name))
		_ = os.MkdirAll(filepath.Dir(p), 0o755)
		if err := os.WriteFile(p, []byte(files[name]), 0o644); err != nil {
			t.Fatal(err)
		}
		stored = append(stored, domain.StoredFile{Path: name, Size: int64(len(files[name]))})
	}
	// A record pointing outside the job dir must never be served.
	stored = append(stored, domain.StoredFile{Path: "../../etc/passwd", Size: 1})

	lookup := mapLookup{testID: {
		ID:        testID,
		Title:     "Война и мир <b>",
		Files:     stored,
		Size:      16,
		ExpiresAt: time.Now().Add(time.Hour),
	}}
	labels := WebLabels{Lang: "en", Files: "Files", DownloadAll: "Download all", ExpiresAt: "until", Download: "Download",
		FormatSize: func(n int64) string { return fmt.Sprintf("%d B", n) }}
	return NewWebServer(":0", dir, lookup, labels, log.New(io.Discard)), dir
}

func get(t *testing.T, h http.Handler, url string, hdr ...string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, url, nil)
	for i := 0; i+1 < len(hdr); i += 2 {
		req.Header.Set(hdr[i], hdr[i+1])
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	return rec
}

func TestWebPage(t *testing.T) {
	ws, _ := newTestWeb(t)
	rec := get(t, ws.Handler(), "/a/"+testID+"/")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	body := rec.Body.String()
	for _, want := range []string{"Война и мир &lt;b&gt;", `src="f/0"`, `href="f/1?dl=1"`, `href="all.zip"`, "cd1/02.mp3"} {
		if !strings.Contains(body, want) {
			t.Errorf("page does not contain %q", want)
		}
	}

	if rec := get(t, ws.Handler(), "/a/"+testID); rec.Code != http.StatusMovedPermanently {
		t.Errorf("no-slash status = %d, want redirect", rec.Code)
	}
	if rec := get(t, ws.Handler(), "/a/ffffffffffffffffffffffffffffffff/"); rec.Code != http.StatusNotFound {
		t.Errorf("unknown id status = %d", rec.Code)
	}
}

func TestWebFile(t *testing.T) {
	ws, _ := newTestWeb(t)
	h := ws.Handler()

	rec := get(t, h, "/a/"+testID+"/f/1?dl=1")
	if rec.Code != http.StatusOK || rec.Body.String() != "second-file" {
		t.Fatalf("status=%d body=%q", rec.Code, rec.Body.String())
	}
	if cd := rec.Header().Get("Content-Disposition"); !strings.HasPrefix(cd, "attachment") || !strings.Contains(cd, "02.mp3") {
		t.Errorf("Content-Disposition = %q", cd)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "audio/mpeg" {
		t.Errorf("Content-Type = %q", ct)
	}

	rec = get(t, h, "/a/"+testID+"/f/1", "Range", "bytes=0-5")
	if rec.Code != http.StatusPartialContent || rec.Body.String() != "second" {
		t.Errorf("range: status=%d body=%q", rec.Code, rec.Body.String())
	}

	for _, n := range []string{"2", "9", "-1", "x"} {
		if rec := get(t, h, "/a/"+testID+"/f/"+n); rec.Code != http.StatusNotFound {
			t.Errorf("file %s status = %d, want 404", n, rec.Code)
		}
	}
}

func TestWebZip(t *testing.T) {
	ws, _ := newTestWeb(t)
	// Drop the bogus record so the archive completes.
	d, _ := ws.lookup.GetDownload(testID)
	d.Files = d.Files[:2]

	rec := get(t, ws.Handler(), "/a/"+testID+"/all.zip")
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d", rec.Code)
	}
	zr, err := zip.NewReader(bytes.NewReader(rec.Body.Bytes()), int64(rec.Body.Len()))
	if err != nil {
		t.Fatalf("zip: %v", err)
	}
	if len(zr.File) != 2 || zr.File[1].Name != "Война и мир _b_/cd1/02.mp3" {
		t.Fatalf("zip entries = %v", zr.File)
	}
	rc, _ := zr.File[1].Open()
	content, _ := io.ReadAll(rc)
	if string(content) != "second-file" {
		t.Errorf("zip content = %q", content)
	}
}
