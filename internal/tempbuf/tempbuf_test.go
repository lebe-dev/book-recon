package tempbuf

import (
	"archive/zip"
	"bytes"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func tempDirEntries(t *testing.T, dir string) int {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("read temp dir: %v", err)
	}
	return len(entries)
}

func TestBuffer_ReadsBackWholeStream(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("TMPDIR", dir)

	payload := bytes.Repeat([]byte("book"), 1024)

	buf, err := Buffer(bytes.NewReader(payload))
	if err != nil {
		t.Fatalf("buffer: %v", err)
	}
	if buf.Size() != int64(len(payload)) {
		t.Fatalf("size = %d, want %d", buf.Size(), len(payload))
	}

	rc, err := buf.Rewound()
	if err != nil {
		t.Fatalf("rewound: %v", err)
	}
	got, err := io.ReadAll(rc)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatal("content mismatch")
	}

	if err := rc.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	if n := tempDirEntries(t, dir); n != 0 {
		t.Fatalf("temp files left after close: %d", n)
	}
}

func TestBuffer_ReaderAtSupportsZip(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("TMPDIR", dir)

	var archive bytes.Buffer
	zw := zip.NewWriter(&archive)
	w, err := zw.Create("book.fb2")
	if err != nil {
		t.Fatalf("create zip entry: %v", err)
	}
	if _, err := w.Write([]byte("<fb2/>")); err != nil {
		t.Fatalf("write zip entry: %v", err)
	}
	if err := zw.Close(); err != nil {
		t.Fatalf("close zip: %v", err)
	}

	buf, err := Buffer(bytes.NewReader(archive.Bytes()))
	if err != nil {
		t.Fatalf("buffer: %v", err)
	}

	zr, err := zip.NewReader(buf.ReaderAt(), buf.Size())
	if err != nil {
		t.Fatalf("zip reader: %v", err)
	}
	entry, err := zr.File[0].Open()
	if err != nil {
		t.Fatalf("open entry: %v", err)
	}

	rc := buf.Wrap(entry)
	got, err := io.ReadAll(rc)
	if err != nil {
		t.Fatalf("read entry: %v", err)
	}
	if string(got) != "<fb2/>" {
		t.Fatalf("entry content = %q", got)
	}

	if err := rc.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	if n := tempDirEntries(t, dir); n != 0 {
		t.Fatalf("temp files left after close: %d", n)
	}
}

func TestBuffer_StopsAtLimit(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("TMPDIR", dir)

	buf, err := BufferLimit(strings.NewReader(strings.Repeat("x", 100)), 10)
	if err != nil {
		t.Fatalf("buffer: %v", err)
	}
	defer func() { _ = buf.Close() }()

	if buf.Size() != 11 {
		t.Fatalf("size = %d, want limit+1 = 11", buf.Size())
	}
}

func TestBuffer_CloseRemovesFileOnce(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("TMPDIR", dir)

	buf, err := Buffer(strings.NewReader("data"))
	if err != nil {
		t.Fatalf("buffer: %v", err)
	}
	name := buf.file.Name()
	if filepath.Dir(name) != dir {
		t.Fatalf("temp file created outside %s: %s", dir, name)
	}
	if err := buf.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}
	if err := buf.Close(); err != nil {
		t.Fatalf("second close: %v", err)
	}
}
