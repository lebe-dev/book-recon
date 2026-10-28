package audiobook

import (
	"archive/zip"
	"context"
	"errors"
	"fmt"
	"html/template"
	"io"
	"mime"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

// DownloadLookup returns a completed, non-expired download by its ID.
type DownloadLookup interface {
	GetDownload(id string) (*domain.AudiobookDownload, bool)
}

// WebLabels are the localized strings of the download page.
type WebLabels struct {
	Lang        string
	Files       string
	DownloadAll string
	ExpiresAt   string
	Download    string
	FormatSize  func(int64) string
}

// WebServer serves completed audiobooks over plain HTTP. It's meant to sit
// behind a reverse proxy (nginx) that terminates TLS.
type WebServer struct {
	server *http.Server
	dir    string
	lookup DownloadLookup
	labels WebLabels
	logger *log.Logger
}

func NewWebServer(listen, dir string, lookup DownloadLookup, labels WebLabels, logger *log.Logger) *WebServer {
	ws := &WebServer{dir: dir, lookup: lookup, labels: labels, logger: logger}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "ok")
	})
	mux.HandleFunc("GET /a/{id}", func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, r.URL.Path+"/", http.StatusMovedPermanently)
	})
	mux.HandleFunc("GET /a/{id}/{$}", ws.handlePage)
	mux.HandleFunc("GET /a/{id}/all.zip", ws.handleZip)
	mux.HandleFunc("GET /a/{id}/f/{n}", ws.handleFile)

	ws.server = &http.Server{
		Addr:              listen,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
		// No WriteTimeout: files can be large and clients slow.
	}
	return ws
}

// Handler exposes the router (used by tests).
func (ws *WebServer) Handler() http.Handler { return ws.server.Handler }

// Start serves until Shutdown. Returns nil on graceful shutdown.
func (ws *WebServer) Start() error {
	ws.logger.Info("audiobook web server listening", "addr", ws.server.Addr)
	if err := ws.server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

func (ws *WebServer) Shutdown(ctx context.Context) error {
	return ws.server.Shutdown(ctx)
}

var pageTmpl = template.Must(template.New("page").Parse(`<!doctype html>
<html lang="{{.Lang}}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>{{.Title}}</title>
<style>
:root { color-scheme: light dark; --fg: #1d1d1f; --bg: #fafafa; --muted: #6e6e73; --line: #e0e0e5; --accent: #0a66d8; }
@media (prefers-color-scheme: dark) { :root { --fg: #f2f2f7; --bg: #161618; --muted: #a1a1a6; --line: #2c2c30; --accent: #4c9bff; } }
* { box-sizing: border-box; }
body { margin: 0; padding: 24px 16px; font: 15px/1.45 system-ui, -apple-system, Segoe UI, Roboto, sans-serif; color: var(--fg); background: var(--bg); }
main { max-width: 760px; margin: 0 auto; }
h1 { font-size: 22px; margin: 0 0 6px; overflow-wrap: anywhere; }
.meta { color: var(--muted); margin-bottom: 18px; }
.btn { display: inline-block; padding: 9px 16px; border-radius: 8px; background: var(--accent); color: #fff; text-decoration: none; font-weight: 600; }
ul { list-style: none; padding: 0; margin: 20px 0 0; }
li { padding: 12px 0; border-top: 1px solid var(--line); }
.name { overflow-wrap: anywhere; }
.row { display: flex; gap: 12px; align-items: center; margin-top: 6px; }
.row audio { flex: 1; min-width: 0; height: 36px; }
.row a { color: var(--accent); white-space: nowrap; }
.size { color: var(--muted); font-size: 13px; white-space: nowrap; }
</style>
</head>
<body>
<main>
<h1>{{.Title}}</h1>
<div class="meta">{{.FilesLabel}}: {{len .Files}} · {{.TotalSize}} · {{.ExpiresLabel}} {{.ExpiresAt}}</div>
<a class="btn" href="all.zip">{{.DownloadAllLabel}}</a>
<ul>
{{range .Files}}<li>
<div class="name">{{.Path}} <span class="size">{{.Size}}</span></div>
<div class="row"><audio controls preload="none" src="f/{{.N}}"></audio><a href="f/{{.N}}?dl=1">{{$.DownloadLabel}}</a></div>
</li>
{{end}}</ul>
</main>
</body>
</html>
`))

type pageFile struct {
	N    int
	Path string
	Size string
}

func (ws *WebServer) handlePage(w http.ResponseWriter, r *http.Request) {
	d, ok := ws.lookup.GetDownload(r.PathValue("id"))
	if !ok {
		http.NotFound(w, r)
		return
	}

	files := make([]pageFile, len(d.Files))
	for i, f := range d.Files {
		files[i] = pageFile{N: i, Path: f.Path, Size: ws.labels.FormatSize(f.Size)}
	}

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("X-Robots-Tag", "noindex, nofollow")
	w.Header().Set("Cache-Control", "no-store")
	err := pageTmpl.Execute(w, map[string]any{
		"Lang":             ws.labels.Lang,
		"Title":            d.Title,
		"Files":            files,
		"TotalSize":        ws.labels.FormatSize(d.Size),
		"FilesLabel":       ws.labels.Files,
		"ExpiresLabel":     ws.labels.ExpiresAt,
		"ExpiresAt":        d.ExpiresAt.UTC().Format("2006-01-02 15:04 UTC"),
		"DownloadAllLabel": ws.labels.DownloadAll,
		"DownloadLabel":    ws.labels.Download,
	})
	if err != nil {
		ws.logger.Warn("render audiobook page", "id", d.ID, "error", err)
	}
}

func (ws *WebServer) handleFile(w http.ResponseWriter, r *http.Request) {
	d, ok := ws.lookup.GetDownload(r.PathValue("id"))
	if !ok {
		http.NotFound(w, r)
		return
	}
	n, err := strconv.Atoi(r.PathValue("n"))
	if err != nil || n < 0 || n >= len(d.Files) {
		http.NotFound(w, r)
		return
	}

	sf := d.Files[n]
	p, err := ws.filePath(d.ID, sf.Path)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	f, err := os.Open(p)
	if err != nil {
		ws.logger.Warn("open audiobook file", "id", d.ID, "path", sf.Path, "error", err)
		http.NotFound(w, r)
		return
	}
	defer func() { _ = f.Close() }()

	st, err := f.Stat()
	if err != nil {
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}

	disposition := "inline"
	if r.URL.Query().Has("dl") {
		disposition = "attachment"
	}
	w.Header().Set("Content-Disposition", mime.FormatMediaType(disposition, map[string]string{"filename": path.Base(sf.Path)}))
	w.Header().Set("X-Robots-Tag", "noindex, nofollow")
	if ct := mime.TypeByExtension(strings.ToLower(path.Ext(sf.Path))); ct != "" {
		w.Header().Set("Content-Type", ct)
	}
	// ServeContent handles Range requests, so audio seeking works.
	http.ServeContent(w, r, "", st.ModTime(), f)
}

// handleZip streams all files as an uncompressed zip: audio doesn't compress,
// and storing avoids CPU load and temp files.
func (ws *WebServer) handleZip(w http.ResponseWriter, r *http.Request) {
	d, ok := ws.lookup.GetDownload(r.PathValue("id"))
	if !ok {
		http.NotFound(w, r)
		return
	}

	root := safeName(d.Title)
	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", mime.FormatMediaType("attachment", map[string]string{"filename": root + ".zip"}))
	w.Header().Set("X-Robots-Tag", "noindex, nofollow")

	zw := zip.NewWriter(w)
	for _, sf := range d.Files {
		if err := ws.addToZip(zw, d.ID, root, sf); err != nil {
			// Headers are already sent; the client gets a truncated archive.
			ws.logger.Warn("stream audiobook zip", "id", d.ID, "path", sf.Path, "error", err)
			return
		}
	}
	if err := zw.Close(); err != nil {
		ws.logger.Warn("finish audiobook zip", "id", d.ID, "error", err)
	}
}

func (ws *WebServer) addToZip(zw *zip.Writer, id, root string, sf domain.StoredFile) error {
	p, err := ws.filePath(id, sf.Path)
	if err != nil {
		return err
	}
	f, err := os.Open(p)
	if err != nil {
		return err
	}
	defer func() { _ = f.Close() }()

	st, err := f.Stat()
	if err != nil {
		return err
	}
	hdr := &zip.FileHeader{
		Name:     root + "/" + sf.Path,
		Method:   zip.Store,
		Modified: st.ModTime(),
	}
	out, err := zw.CreateHeader(hdr)
	if err != nil {
		return err
	}
	_, err = io.Copy(out, f)
	return err
}

// filePath resolves a stored file path and makes sure it stays inside the job dir.
func (ws *WebServer) filePath(id, rel string) (string, error) {
	if !jobIDPattern.MatchString(id) {
		return "", fmt.Errorf("invalid id")
	}
	base := filepath.Join(ws.dir, id)
	p := filepath.Join(base, filepath.FromSlash(rel))
	r, err := filepath.Rel(base, p)
	if err != nil || r == "." || r == ".." || strings.HasPrefix(r, ".."+string(filepath.Separator)) {
		return "", fmt.Errorf("path %q escapes download dir", rel)
	}
	return p, nil
}

// safeName turns a title into something usable as a file/dir name.
func safeName(s string) string {
	s = strings.Map(func(r rune) rune {
		switch r {
		case '/', '\\', ':', '*', '?', '"', '<', '>', '|':
			return '_'
		}
		if r < 0x20 {
			return -1
		}
		return r
	}, s)
	s = strings.TrimSpace(s)
	if r := []rune(s); len(r) > 100 {
		s = strings.TrimSpace(string(r[:100]))
	}
	if s == "" || s == "." || s == ".." {
		return "audiobook"
	}
	return s
}
