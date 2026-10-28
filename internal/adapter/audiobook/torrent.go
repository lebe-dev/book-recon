package audiobook

import (
	"bytes"
	"context"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	g "github.com/anacrolix/generics"
	"github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/metainfo"
	"github.com/anacrolix/torrent/storage"
	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

// jobIDPattern matches download directory names; anything else in the root is left alone.
var jobIDPattern = regexp.MustCompile(`^[0-9a-f]{32}$`)

// DownloaderConfig holds torrent client settings for audiobooks.
type DownloaderConfig struct {
	// Root directory; every job gets its own <root>/<jobID> subdirectory.
	Dir string
	// Abort when no new data arrives for this long.
	StallTimeout time.Duration
	// Upload to peers while downloading (helps tracker ratio). No seeding after completion.
	AllowUpload bool
	// How often progress is sampled.
	PollInterval time.Duration
	// Tests only: keep the client off the network.
	disableNetwork bool
}

// Downloader downloads selected files of a torrent into per-job directories.
type Downloader struct {
	client *torrent.Client
	config DownloaderConfig
	logger *log.Logger
}

func NewDownloader(cfg DownloaderConfig, logger *log.Logger) (*Downloader, error) {
	if err := os.MkdirAll(cfg.Dir, 0o755); err != nil {
		return nil, fmt.Errorf("audiobook: create download dir: %w", err)
	}
	if cfg.PollInterval <= 0 {
		cfg.PollInterval = 2 * time.Second
	}

	tcfg := torrent.NewDefaultClientConfig()
	// Every torrent gets its own storage; this default is never used for data,
	// it only keeps the client from creating piece-completion DBs in the root.
	tcfg.DefaultStorage = newFileStorage(cfg.Dir)
	tcfg.DataDir = cfg.Dir
	tcfg.NoUpload = !cfg.AllowUpload
	tcfg.Seed = false
	tcfg.DisableIPv6 = true
	tcfg.ListenPort = 0
	if cfg.disableNetwork {
		tcfg.NoDHT = true
		tcfg.DisableTrackers = true
		tcfg.ListenHost = torrent.LoopbackListenHost
	}

	client, err := torrent.NewClient(tcfg)
	if err != nil {
		return nil, fmt.Errorf("audiobook: create torrent client: %w", err)
	}

	return &Downloader{client: client, config: cfg, logger: logger}, nil
}

// Parse reads the file list from raw .torrent data. Padding files and files
// with unsafe paths are skipped.
func (d *Downloader) Parse(data []byte) (*domain.TorrentInfo, error) {
	mi, err := metainfo.Load(bytes.NewReader(data))
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeProviderError, "parse torrent", err)
	}
	info, err := mi.UnmarshalInfo()
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeProviderError, "parse torrent info", err)
	}

	ti := &domain.TorrentInfo{
		InfoHash: mi.HashInfoBytes().HexString(),
		Name:     info.BestName(),
	}
	for i, fi := range info.UpvertedFiles() {
		if strings.Contains(fi.Attr, "p") {
			continue
		}
		p, err := relFilePath(&info, &fi)
		if err != nil {
			continue
		}
		ti.Files = append(ti.Files, domain.TorrentFile{Index: i, Path: filepath.ToSlash(p), Size: fi.Length})
	}
	return ti, nil
}

// Download fetches only the files with the given indexes. Blocks until all of
// them are complete, the context is done or the download stalls. On success the
// job directory contains exactly the selected files. On failure the caller
// removes the job directory with Remove.
func (d *Downloader) Download(ctx context.Context, jobID string, data []byte, indexes []int, onProgress func(domain.DownloadProgress)) ([]domain.StoredFile, error) {
	mi, err := metainfo.Load(bytes.NewReader(data))
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeProviderError, "parse torrent", err)
	}
	spec, err := torrent.TorrentSpecFromMetaInfoErr(mi)
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeProviderError, "parse torrent info", err)
	}

	jobDir, err := d.jobDir(jobID)
	if err != nil {
		return nil, err
	}
	if err := os.MkdirAll(jobDir, 0o755); err != nil {
		return nil, fmt.Errorf("audiobook: create job dir: %w", err)
	}

	st := newFileStorage(jobDir)
	defer func() { _ = st.Close() }()
	spec.Storage = st

	t, isNew, err := d.client.AddTorrentSpec(spec)
	if err != nil {
		return nil, domain.WrapError(domain.ErrCodeProviderError, "add torrent", err)
	}
	if !isNew {
		// Same torrent is in use by another job; don't touch it.
		return nil, domain.NewError(domain.ErrCodeAlreadyDownloading, "torrent is already being downloaded")
	}
	defer t.Drop()

	select {
	case <-t.GotInfo():
	case <-ctx.Done():
		return nil, ctx.Err()
	}

	info := t.Info()
	all := t.Files()
	selected := make([]*torrent.File, 0, len(indexes))
	stored := make([]domain.StoredFile, 0, len(indexes))
	keep := make(map[string]bool, len(indexes))
	for _, idx := range indexes {
		if idx < 0 || idx >= len(all) {
			return nil, domain.NewError(domain.ErrCodeProviderError, fmt.Sprintf("file index %d out of range", idx))
		}
		f := all[idx]
		fi := f.FileInfo()
		p, err := relFilePath(info, &fi)
		if err != nil {
			return nil, domain.WrapError(domain.ErrCodeProviderError, "unsafe file path", err)
		}
		selected = append(selected, f)
		stored = append(stored, domain.StoredFile{Path: filepath.ToSlash(p), Size: f.Length()})
		keep[filepath.Join(jobDir, p)] = true
	}

	var total int64
	for _, f := range selected {
		total += f.Length()
		f.Download()
	}

	d.logger.Info("audiobook download started", "job", jobID, "torrent", t.Name(), "files", len(selected), "size", total)

	if err := d.wait(ctx, t, selected, total, onProgress); err != nil {
		return nil, err
	}

	// Pieces span file boundaries, so neighbouring files get partially written.
	// Keep only what the user asked for.
	removeUnselected(jobDir, keep, d.logger)

	d.logger.Info("audiobook download complete", "job", jobID, "files", len(selected), "size", total)
	return stored, nil
}

func (d *Downloader) wait(ctx context.Context, t *torrent.Torrent, files []*torrent.File, total int64, onProgress func(domain.DownloadProgress)) error {
	ticker := time.NewTicker(d.config.PollInterval)
	defer ticker.Stop()

	var (
		lastCompleted int64 = -1
		lastChange          = time.Now()
		lastSample          = time.Now()
		gotAnyData    bool
	)

	for {
		var completed int64
		for _, f := range files {
			completed += f.BytesCompleted()
		}

		now := time.Now()
		var speed int64
		if lastCompleted >= 0 {
			if elapsed := now.Sub(lastSample).Seconds(); elapsed > 0 {
				speed = int64(float64(completed-lastCompleted) / elapsed)
			}
		}
		if completed > lastCompleted {
			if lastCompleted >= 0 {
				gotAnyData = true
			}
			lastChange = now
		}
		lastCompleted, lastSample = completed, now

		if onProgress != nil {
			onProgress(domain.DownloadProgress{
				Completed: completed,
				Total:     total,
				Peers:     t.Stats().ActivePeers,
				Speed:     speed,
			})
		}

		if completed >= total {
			return nil
		}

		if d.config.StallTimeout > 0 && now.Sub(lastChange) > d.config.StallTimeout {
			if !gotAnyData && t.Stats().ActivePeers == 0 {
				return domain.NewError(domain.ErrCodeNoSeeders, "no peers available")
			}
			return domain.NewError(domain.ErrCodeStalled,
				fmt.Sprintf("no progress for %s", d.config.StallTimeout))
		}

		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
		}
	}
}

// Remove deletes a job's directory.
func (d *Downloader) Remove(jobID string) error {
	dir, err := d.jobDir(jobID)
	if err != nil {
		return err
	}
	return os.RemoveAll(dir)
}

// RemoveOrphans deletes job directories that are not in keep (e.g. left over
// after a restart in the middle of a download).
func (d *Downloader) RemoveOrphans(keep map[string]bool) error {
	entries, err := os.ReadDir(d.config.Dir)
	if err != nil {
		return err
	}
	for _, e := range entries {
		if !e.IsDir() || !jobIDPattern.MatchString(e.Name()) || keep[e.Name()] {
			continue
		}
		path := filepath.Join(d.config.Dir, e.Name())
		if err := os.RemoveAll(path); err != nil {
			d.logger.Warn("failed to remove orphan audiobook dir", "path", path, "error", err)
			continue
		}
		d.logger.Info("removed orphan audiobook dir", "path", path)
	}
	return nil
}

// Close shuts down the torrent client.
func (d *Downloader) Close() {
	d.client.Close()
}

func (d *Downloader) jobDir(jobID string) (string, error) {
	if !jobIDPattern.MatchString(jobID) {
		return "", fmt.Errorf("audiobook: invalid job id %q", jobID)
	}
	return filepath.Join(d.config.Dir, jobID), nil
}

func newFileStorage(dir string) storage.ClientImplCloser {
	return storage.NewFileOpts(storage.NewFileClientOpts{
		ClientBaseDir:   dir,
		PieceCompletion: storage.NewMapPieceCompletion(),
		UsePartFiles:    g.Some(false),
		FilePathMaker: func(opts storage.FilePathMakerOpts) string {
			p, err := relFilePath(opts.Info, opts.File)
			if err != nil {
				// Never offered for selection; park it under a hidden name so the
				// rest of the torrent stays usable. Removed after the download.
				return filepath.Join(".invalid", fmt.Sprintf("%d", opts.File.TorrentOffset))
			}
			return p
		},
	})
}

// relFilePath is the on-disk path of a torrent file relative to the job dir.
// The torrent name is not used as a top-level directory: the job dir already
// isolates torrents from each other.
func relFilePath(info *metainfo.Info, fi *metainfo.FileInfo) (string, error) {
	comps := fi.BestPath()
	if len(comps) == 0 {
		comps = []string{info.BestName()}
	}
	p, err := storage.ToSafeFilePath(comps...)
	if err != nil {
		return "", err
	}
	if p == "" || p == "." || filepath.IsAbs(p) {
		return "", fmt.Errorf("invalid path %q", p)
	}
	return p, nil
}

func removeUnselected(dir string, keep map[string]bool, logger *log.Logger) {
	_ = filepath.WalkDir(dir, func(path string, e fs.DirEntry, err error) error {
		if err != nil || e.IsDir() || keep[path] {
			return nil
		}
		if err := os.Remove(path); err != nil {
			logger.Warn("failed to remove unselected file", "path", path, "error", err)
		}
		return nil
	})
	removeEmptyDirs(dir)
}

func removeEmptyDirs(dir string) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		return
	}
	for _, e := range entries {
		if e.IsDir() {
			sub := filepath.Join(dir, e.Name())
			removeEmptyDirs(sub)
			_ = os.Remove(sub) // fails if not empty, which is fine
		}
	}
}
