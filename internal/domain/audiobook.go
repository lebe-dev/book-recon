package domain

import (
	"context"
	"crypto/sha256"
	"fmt"
	"time"
)

// Audiobook is a single audiobook distribution found on the tracker.
type Audiobook struct {
	ID         string
	Title      string
	Size       int64
	Seeders    int
	Peers      int
	TorrentURL string
}

// NewAudiobook fills in a deterministic ID derived from the torrent URL.
func NewAudiobook(a Audiobook) Audiobook {
	hash := sha256.Sum256([]byte(a.TorrentURL))
	a.ID = fmt.Sprintf("%x", hash[:6]) // 12 hex chars
	return a
}

// TorrentFile is a file inside a torrent.
type TorrentFile struct {
	Index int    // position in the torrent's file list
	Path  string // slash-separated path relative to the torrent root
	Size  int64
}

// TorrentInfo is the parsed content of a .torrent file.
type TorrentInfo struct {
	InfoHash string
	Name     string
	Files    []TorrentFile
}

// DownloadProgress is a snapshot of an in-flight torrent download.
type DownloadProgress struct {
	Completed int64
	Total     int64
	Peers     int
	Speed     int64 // bytes per second
}

// StoredFile is a downloaded file kept on disk for web delivery.
type StoredFile struct {
	Path string // slash-separated path relative to the download directory
	Size int64
}

type AudiobookJobStatus string

const (
	AudiobookJobQueued      AudiobookJobStatus = "queued"
	AudiobookJobDownloading AudiobookJobStatus = "downloading"
	AudiobookJobCompleted   AudiobookJobStatus = "completed"
	AudiobookJobFailed      AudiobookJobStatus = "failed"
	AudiobookJobCancelled   AudiobookJobStatus = "cancelled"
)

// AudiobookJob is a download job: from the moment the user confirms the file
// selection until the files are published (or the job fails).
type AudiobookJob struct {
	ID              string
	TelegramID      int64
	ChatID          int64
	StatusMessageID int
	Title           string
	FileCount       int
	TotalSize       int64
	Status          AudiobookJobStatus
	Progress        DownloadProgress
	QueuePosition   int  // 1-based, only for queued jobs
	WasQueued       bool // the job had to wait for a free download slot
	Err             error
	CreatedAt       time.Time
	ExpiresAt       time.Time // set for completed jobs
}

type AudiobookEventKind string

const (
	AudiobookEventQueued      AudiobookEventKind = "queued"
	AudiobookEventStarted     AudiobookEventKind = "started"
	AudiobookEventProgress    AudiobookEventKind = "progress"
	AudiobookEventCompleted   AudiobookEventKind = "completed"
	AudiobookEventFailed      AudiobookEventKind = "failed"
	AudiobookEventCancelled   AudiobookEventKind = "cancelled"
	AudiobookEventInterrupted AudiobookEventKind = "interrupted" // lost on restart
)

// AudiobookDownload is a persisted download record. Completed records back the
// public web links until they expire.
type AudiobookDownload struct {
	ID              string
	TelegramID      int64
	ChatID          int64
	StatusMessageID int
	Title           string
	Status          AudiobookJobStatus
	Files           []StoredFile
	Size            int64
	CreatedAt       time.Time
	ExpiresAt       time.Time
}

type AudiobookDownloadRepository interface {
	Save(ctx context.Context, d *AudiobookDownload) error
	List(ctx context.Context) ([]AudiobookDownload, error)
	Delete(ctx context.Context, id string) error
}

// AppSettingsRepository stores global runtime settings changed from the bot.
type AppSettingsRepository interface {
	// GetBool returns found=false when the key was never set.
	GetBool(ctx context.Context, key string) (value bool, found bool, err error)
	SetBool(ctx context.Context, key string, value bool) error
}
