package rutracker

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/anacrolix/torrent"
	"github.com/anacrolix/torrent/metainfo"
	"github.com/anacrolix/torrent/storage"
	"github.com/charmbracelet/log"
	"golang.org/x/sync/semaphore"
)

// TorrentConfig holds configuration for TorrentManager.
type TorrentConfig struct {
	DownloadDir     string
	DownloadTimeout time.Duration
	MaxTorrentSize  int64
	MaxConcurrent   int64
}

// DownloadedFile represents a file downloaded from a torrent.
type DownloadedFile struct {
	Path string // absolute path on disk
	Name string // original filename from torrent
	Size int64
}

// TorrentManager wraps anacrolix/torrent client.
type TorrentManager struct {
	client *torrent.Client
	mu     sync.Mutex
	sem    *semaphore.Weighted
	config TorrentConfig
	logger *log.Logger
}

// NewTorrentManager creates a TorrentManager with a long-lived torrent client.
func NewTorrentManager(cfg TorrentConfig, logger *log.Logger) (*TorrentManager, error) {
	if err := os.MkdirAll(cfg.DownloadDir, 0o755); err != nil {
		return nil, fmt.Errorf("rutracker: create download dir: %w", err)
	}

	tcfg := torrent.NewDefaultClientConfig()
	tcfg.DefaultStorage = storage.NewFileOpts(storage.NewFileClientOpts{
		ClientBaseDir:   cfg.DownloadDir,
		PieceCompletion: storage.NewMapPieceCompletion(),
	})
	tcfg.DataDir = cfg.DownloadDir
	tcfg.NoUpload = true
	tcfg.Seed = false
	tcfg.DisableIPv6 = true
	tcfg.ListenPort = 0

	client, err := torrent.NewClient(tcfg)
	if err != nil {
		return nil, fmt.Errorf("rutracker: create torrent client: %w", err)
	}

	maxConcurrent := cfg.MaxConcurrent
	if maxConcurrent <= 0 {
		maxConcurrent = 3
	}

	return &TorrentManager{
		client: client,
		sem:    semaphore.NewWeighted(maxConcurrent),
		config: cfg,
		logger: logger,
	}, nil
}

// Download downloads files from torrent data (raw .torrent bytes).
// Blocks until download completes or context is cancelled.
func (tm *TorrentManager) Download(ctx context.Context, torrentData []byte) ([]DownloadedFile, metainfo.Hash, error) {
	if err := tm.sem.Acquire(ctx, 1); err != nil {
		return nil, metainfo.Hash{}, fmt.Errorf("rutracker: semaphore acquire: %w", err)
	}
	defer tm.sem.Release(1)

	mi, err := metainfo.Load(bytes.NewReader(torrentData))
	if err != nil {
		return nil, metainfo.Hash{}, fmt.Errorf("rutracker: parse torrent: %w", err)
	}

	tm.mu.Lock()
	t, err := tm.client.AddTorrent(mi)
	tm.mu.Unlock()
	if err != nil {
		return nil, metainfo.Hash{}, fmt.Errorf("rutracker: add torrent: %w", err)
	}

	infoHash := t.InfoHash()
	tm.logger.Debug("torrent added", "hash", infoHash.HexString(), "name", t.Name())

	// Wait for info (metadata).
	select {
	case <-t.GotInfo():
	case <-ctx.Done():
		tm.cleanupTorrent(t)
		return nil, infoHash, fmt.Errorf("rutracker: timeout waiting for torrent info: %w", ctx.Err())
	}

	// Check total size.
	totalSize := t.Length()
	if tm.config.MaxTorrentSize > 0 && totalSize > tm.config.MaxTorrentSize {
		tm.cleanupTorrent(t)
		return nil, infoHash, fmt.Errorf("rutracker: torrent too large: %d bytes (max %d)", totalSize, tm.config.MaxTorrentSize)
	}

	tm.logger.Debug("downloading torrent", "hash", infoHash.HexString(), "size", totalSize, "files", len(t.Files()))

	// Start download.
	t.DownloadAll()

	// Wait for completion.
	if !tm.waitForCompletion(ctx, t) {
		tm.cleanupTorrent(t)
		return nil, infoHash, fmt.Errorf("rutracker: download timeout: %w", ctx.Err())
	}

	// Collect downloaded files.
	var files []DownloadedFile
	for _, f := range t.Files() {
		absPath := filepath.Join(tm.config.DownloadDir, f.Path())
		files = append(files, DownloadedFile{
			Path: absPath,
			Name: filepath.Base(f.Path()),
			Size: f.Length(),
		})
	}

	tm.logger.Info("torrent download complete", "hash", infoHash.HexString(), "files", len(files))
	return files, infoHash, nil
}

// Cleanup removes a torrent and its data from disk.
func (tm *TorrentManager) Cleanup(infoHash metainfo.Hash) error {
	tm.mu.Lock()
	t, ok := tm.client.Torrent(infoHash)
	if ok {
		t.Drop()
	}
	tm.mu.Unlock()

	// Remove downloaded data.
	if ok {
		dataPath := filepath.Join(tm.config.DownloadDir, t.Name())
		if err := os.RemoveAll(dataPath); err != nil {
			tm.logger.Warn("failed to cleanup torrent data", "hash", infoHash.HexString(), "error", err)
			return err
		}
	}

	tm.logger.Debug("torrent cleaned up", "hash", infoHash.HexString())
	return nil
}

// CleanupStale removes all data from the download directory (called at startup).
func (tm *TorrentManager) CleanupStale() error {
	entries, err := os.ReadDir(tm.config.DownloadDir)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	for _, entry := range entries {
		path := filepath.Join(tm.config.DownloadDir, entry.Name())
		if err := os.RemoveAll(path); err != nil {
			tm.logger.Warn("failed to remove stale data", "path", path, "error", err)
		}
	}

	tm.logger.Info("stale torrent data cleaned up", "entries", len(entries))
	return nil
}

// Close shuts down the torrent client.
func (tm *TorrentManager) Close() {
	tm.client.Close()
}

func (tm *TorrentManager) waitForCompletion(ctx context.Context, t *torrent.Torrent) bool {
	select {
	case <-t.Complete().On():
		return true
	case <-ctx.Done():
		return false
	}
}

func (tm *TorrentManager) cleanupTorrent(t *torrent.Torrent) {
	t.Drop()
	dataPath := filepath.Join(tm.config.DownloadDir, t.Name())
	_ = os.RemoveAll(dataPath)
}
