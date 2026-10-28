package audiobook

import (
	"bytes"
	"context"
	"io"
	"os"
	"path/filepath"
	"sort"
	"testing"
	"time"

	"github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/bencode"
	"github.com/anacrolix/torrent/metainfo"
	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

// buildTorrent creates a multi-file torrent from files under <dir>/book.
func buildTorrent(t *testing.T, dir string, files map[string]int) []byte {
	t.Helper()
	for name, size := range files {
		p := filepath.Join(dir, "book", name)
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			t.Fatal(err)
		}
		data := bytes.Repeat([]byte{byte(len(name))}, size)
		if err := os.WriteFile(p, data, 0o644); err != nil {
			t.Fatal(err)
		}
	}

	info := metainfo.Info{PieceLength: 16 << 10}
	if err := info.BuildFromFilePath(filepath.Join(dir, "book")); err != nil {
		t.Fatal(err)
	}
	infoBytes, err := bencode.Marshal(info)
	if err != nil {
		t.Fatal(err)
	}
	mi := metainfo.MetaInfo{InfoBytes: infoBytes}
	var buf bytes.Buffer
	if err := mi.Write(&buf); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func newTestDownloader(t *testing.T) *Downloader {
	t.Helper()
	d, err := NewDownloader(DownloaderConfig{
		Dir:            t.TempDir(),
		StallTimeout:   20 * time.Second,
		PollInterval:   50 * time.Millisecond,
		disableNetwork: true,
	}, log.New(io.Discard))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(d.Close)
	return d
}

func TestParse(t *testing.T) {
	data := buildTorrent(t, t.TempDir(), map[string]int{
		"cd1/01.mp3": 1000,
		"cd1/02.mp3": 2000,
		"cover.jpg":  10,
	})
	d := newTestDownloader(t)

	ti, err := d.Parse(data)
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	if ti.Name != "book" || len(ti.InfoHash) != 40 {
		t.Errorf("Name=%q InfoHash=%q", ti.Name, ti.InfoHash)
	}

	got := map[string]int64{}
	for _, f := range ti.Files {
		got[f.Path] = f.Size
	}
	want := map[string]int64{"cd1/01.mp3": 1000, "cd1/02.mp3": 2000, "cover.jpg": 10}
	if len(got) != len(want) {
		t.Fatalf("files = %v", got)
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("file %s size = %d, want %d", k, got[k], v)
		}
	}

	if _, err := d.Parse([]byte("garbage")); err == nil {
		t.Error("Parse(garbage) expected error")
	}
}

func TestDownloadSelectedFiles(t *testing.T) {
	seedDir := t.TempDir()
	data := buildTorrent(t, seedDir, map[string]int{
		"cd1/01.mp3": 40 << 10,
		"cd1/02.mp3": 40 << 10,
		"cd2/03.mp3": 30 << 10,
		"cover.jpg":  10 << 10,
	})

	// Seeder with the full data.
	scfg := torrent.TestingConfig(t)
	scfg.DataDir = seedDir
	scfg.Seed = true
	scfg.DisableIPv6 = true
	scfg.MaxAllocPeerRequestDataPerConn = 1 << 20
	seeder, err := torrent.NewClient(scfg)
	if err != nil {
		t.Fatal(err)
	}
	defer seeder.Close()
	mi, err := metainfo.Load(bytes.NewReader(data))
	if err != nil {
		t.Fatal(err)
	}
	st, err := seeder.AddTorrent(mi)
	if err != nil {
		t.Fatal(err)
	}
	if err := st.VerifyDataContext(context.Background()); err != nil {
		t.Fatal(err)
	}

	d := newTestDownloader(t)
	ti, err := d.Parse(data)
	if err != nil {
		t.Fatal(err)
	}
	var indexes []int
	for _, f := range ti.Files {
		if f.Path == "cd1/02.mp3" || f.Path == "cd2/03.mp3" {
			indexes = append(indexes, f.Index)
		}
	}

	// Connect leecher to seeder once the job adds the torrent.
	go func() {
		for i := 0; i < 200; i++ {
			if lt, ok := d.client.Torrent(mi.HashInfoBytes()); ok {
				lt.AddClientPeer(seeder)
				return
			}
			time.Sleep(10 * time.Millisecond)
		}
	}()

	const jobID = "0123456789abcdef0123456789abcdef"
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()

	var last domain.DownloadProgress
	stored, err := d.Download(ctx, jobID, data, indexes, func(p domain.DownloadProgress) { last = p })
	if err != nil {
		t.Fatalf("Download: %v", err)
	}
	if last.Completed != last.Total || last.Total != 70<<10 {
		t.Errorf("last progress = %+v", last)
	}

	sort.Slice(stored, func(i, j int) bool { return stored[i].Path < stored[j].Path })
	if len(stored) != 2 || stored[0].Path != "cd1/02.mp3" || stored[1].Path != "cd2/03.mp3" {
		t.Fatalf("stored = %+v", stored)
	}

	// Only the selected files remain in the job dir, with the right content.
	var onDisk []string
	jobDir := filepath.Join(d.config.Dir, jobID)
	_ = filepath.Walk(jobDir, func(p string, info os.FileInfo, err error) error {
		if err == nil && !info.IsDir() {
			rel, _ := filepath.Rel(jobDir, p)
			onDisk = append(onDisk, filepath.ToSlash(rel))
		}
		return nil
	})
	sort.Strings(onDisk)
	if len(onDisk) != 2 || onDisk[0] != "cd1/02.mp3" || onDisk[1] != "cd2/03.mp3" {
		t.Errorf("files on disk = %v", onDisk)
	}
	got, _ := os.ReadFile(filepath.Join(jobDir, "cd1", "02.mp3"))
	want, _ := os.ReadFile(filepath.Join(seedDir, "book", "cd1", "02.mp3"))
	if !bytes.Equal(got, want) {
		t.Error("downloaded content differs from source")
	}

	if err := d.Remove(jobID); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(jobDir); !os.IsNotExist(err) {
		t.Error("job dir not removed")
	}
}

func TestDownloadStallsWithoutPeers(t *testing.T) {
	data := buildTorrent(t, t.TempDir(), map[string]int{"01.mp3": 40 << 10, "02.mp3": 1})
	d := newTestDownloader(t)
	d.config.StallTimeout = 200 * time.Millisecond

	_, err := d.Download(context.Background(), "0123456789abcdef0123456789abcdef", data, []int{0}, nil)
	if code, _ := domain.ErrorCodeFrom(err); code != domain.ErrCodeNoSeeders {
		t.Errorf("err = %v, want NO_SEEDERS", err)
	}
}

func TestRemoveOrphans(t *testing.T) {
	d := newTestDownloader(t)
	keepID := "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	orphanID := "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	for _, name := range []string{keepID, orphanID, "not-a-job"} {
		if err := os.MkdirAll(filepath.Join(d.config.Dir, name), 0o755); err != nil {
			t.Fatal(err)
		}
	}

	if err := d.RemoveOrphans(map[string]bool{keepID: true}); err != nil {
		t.Fatal(err)
	}

	for name, wantExist := range map[string]bool{keepID: true, orphanID: false, "not-a-job": true} {
		_, err := os.Stat(filepath.Join(d.config.Dir, name))
		if exists := err == nil; exists != wantExist {
			t.Errorf("%s exists=%v, want %v", name, exists, wantExist)
		}
	}
}
