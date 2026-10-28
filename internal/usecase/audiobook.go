package usecase

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"path"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
	"golang.org/x/sync/semaphore"
)

const (
	settingAudiobooksEnabled = "audiobooks_enabled"
	audiobookSessionTTL      = time.Hour
)

// AudiobookSearcher finds audiobook distributions and fetches their .torrent files.
type AudiobookSearcher interface {
	Search(ctx context.Context, query string, limit int) ([]domain.Audiobook, error)
	FetchTorrent(ctx context.Context, torrentURL string) ([]byte, error)
}

// AudiobookDownloader downloads selected torrent files into per-job directories.
type AudiobookDownloader interface {
	Parse(data []byte) (*domain.TorrentInfo, error)
	Download(ctx context.Context, jobID string, data []byte, indexes []int, onProgress func(domain.DownloadProgress)) ([]domain.StoredFile, error)
	Remove(jobID string) error
	RemoveOrphans(keep map[string]bool) error
}

// AudiobookNotifier receives job lifecycle events (implemented by the Telegram bot).
type AudiobookNotifier interface {
	OnAudiobookEvent(kind domain.AudiobookEventKind, job domain.AudiobookJob)
}

type AudiobookConfig struct {
	SearchLimit      int
	MaxDownloadSize  int64 // per job, 0 = unlimited
	MaxFileSize      int64 // per file, 0 = unlimited
	StorageMaxSize   int64 // all downloads on disk, 0 = unlimited
	LinkTTL          time.Duration
	DownloadTimeout  time.Duration
	MaxConcurrent    int
	MaxPerUser       int
	ProgressInterval time.Duration
	FileExtensions   []string // empty or "*" = all files
	PublicURL        string
	CleanupInterval  time.Duration
}

// AudiobookSearchSession holds the latest /audio search of a user.
type AudiobookSearchSession struct {
	Query     string
	Results   []domain.Audiobook
	CreatedAt time.Time
}

// AudiobookStats is an overview for admins.
type AudiobookStats struct {
	Active    int
	Queued    int
	Ready     int
	UsedBytes int64
	MaxBytes  int64
}

type audiobookJob struct {
	domain.AudiobookJob
	torrent         []byte
	indexes         []int
	infoHash        string
	ctx             context.Context
	cancel          context.CancelFunc
	cancelledByUser bool
	lastNotify      time.Time
}

// AudiobookService drives audiobook search, file selection and downloads.
type AudiobookService struct {
	searcher   AudiobookSearcher
	downloader AudiobookDownloader
	repo       domain.AudiobookDownloadRepository
	settings   domain.AppSettingsRepository
	notifier   AudiobookNotifier
	cfg        AudiobookConfig
	logger     *log.Logger
	sem        *semaphore.Weighted

	mu         sync.Mutex
	enabled    bool
	searches   map[int64]*AudiobookSearchSession
	selections map[string]*AudiobookSelection
	jobs       map[string]*audiobookJob
	queue      []string // IDs of queued jobs, oldest first
	downloads  map[string]*domain.AudiobookDownload

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup
	now    func() time.Time
}

func NewAudiobookService(
	searcher AudiobookSearcher,
	downloader AudiobookDownloader,
	repo domain.AudiobookDownloadRepository,
	settings domain.AppSettingsRepository,
	cfg AudiobookConfig,
	logger *log.Logger,
) *AudiobookService {
	if cfg.MaxConcurrent < 1 {
		cfg.MaxConcurrent = 1
	}
	if cfg.MaxPerUser < 1 {
		cfg.MaxPerUser = 1
	}
	if cfg.SearchLimit < 1 {
		cfg.SearchLimit = 30
	}
	if cfg.CleanupInterval <= 0 {
		cfg.CleanupInterval = 5 * time.Minute
	}
	for i, ext := range cfg.FileExtensions {
		cfg.FileExtensions[i] = strings.ToLower(strings.TrimPrefix(strings.TrimSpace(ext), "."))
	}

	ctx, cancel := context.WithCancel(context.Background())
	return &AudiobookService{
		searcher:   searcher,
		downloader: downloader,
		repo:       repo,
		settings:   settings,
		cfg:        cfg,
		logger:     logger,
		sem:        semaphore.NewWeighted(int64(cfg.MaxConcurrent)),
		enabled:    true,
		searches:   make(map[int64]*AudiobookSearchSession),
		selections: make(map[string]*AudiobookSelection),
		jobs:       make(map[string]*audiobookJob),
		downloads:  make(map[string]*domain.AudiobookDownload),
		ctx:        ctx,
		cancel:     cancel,
		now:        time.Now,
	}
}

// SetNotifier must be called before Start.
func (s *AudiobookService) SetNotifier(n AudiobookNotifier) {
	s.notifier = n
}

// Config returns the effective configuration.
func (s *AudiobookService) Config() AudiobookConfig {
	return s.cfg
}

// Start restores state from the database and starts background cleanup.
// Downloads interrupted by a restart are reported to their owners and dropped.
func (s *AudiobookService) Start(ctx context.Context) error {
	if v, found, err := s.settings.GetBool(ctx, settingAudiobooksEnabled); err != nil {
		return fmt.Errorf("load audiobooks setting: %w", err)
	} else if found {
		s.enabled = v
	}

	records, err := s.repo.List(ctx)
	if err != nil {
		return fmt.Errorf("load audiobook downloads: %w", err)
	}

	now := s.now()
	keep := make(map[string]bool)
	for i := range records {
		d := records[i]
		switch {
		case d.Status == domain.AudiobookJobCompleted && now.Before(d.ExpiresAt):
			s.downloads[d.ID] = &d
			keep[d.ID] = true
		case d.Status == domain.AudiobookJobCompleted:
			s.dropDownload(ctx, d.ID)
		default:
			s.logger.Info("audiobook download interrupted by restart", "job", d.ID, "title", d.Title)
			s.notify(domain.AudiobookEventInterrupted, domain.AudiobookJob{
				ID:              d.ID,
				TelegramID:      d.TelegramID,
				ChatID:          d.ChatID,
				StatusMessageID: d.StatusMessageID,
				Title:           d.Title,
				Status:          domain.AudiobookJobFailed,
				Err:             domain.NewError(domain.ErrCodeInterrupted, "interrupted by restart"),
			})
			if err := s.repo.Delete(ctx, d.ID); err != nil {
				s.logger.Warn("failed to delete interrupted audiobook record", "job", d.ID, "error", err)
			}
		}
	}

	if err := s.downloader.RemoveOrphans(keep); err != nil {
		s.logger.Warn("failed to remove orphan audiobook dirs", "error", err)
	}

	s.wg.Add(1)
	go s.cleanupLoop()

	s.logger.Info("audiobook service started", "enabled", s.enabled, "ready_downloads", len(s.downloads))
	return nil
}

// Close cancels running downloads and waits for them. Their DB records stay
// non-completed, so owners are notified on the next start.
func (s *AudiobookService) Close() {
	s.cancel()
	s.wg.Wait()
}

// Enabled reports the runtime switch controlled by admins.
func (s *AudiobookService) Enabled() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.enabled
}

// SetEnabled flips the runtime switch. Running downloads are not affected.
func (s *AudiobookService) SetEnabled(ctx context.Context, enabled bool) error {
	if err := s.settings.SetBool(ctx, settingAudiobooksEnabled, enabled); err != nil {
		return err
	}
	s.mu.Lock()
	s.enabled = enabled
	s.mu.Unlock()
	s.logger.Info("audiobooks runtime switch changed", "enabled", enabled)
	return nil
}

// Search finds audiobooks and remembers the results for paging.
func (s *AudiobookService) Search(ctx context.Context, userID int64, query string) (*AudiobookSearchSession, error) {
	if !s.Enabled() {
		return nil, domain.NewError(domain.ErrCodeFeatureDisabled, "audiobooks are disabled")
	}

	results, err := s.searcher.Search(ctx, query, s.cfg.SearchLimit)
	if err != nil {
		return nil, err
	}
	if len(results) == 0 {
		return nil, domain.NewError(domain.ErrCodeNotFound, "no audiobooks found")
	}

	sess := &AudiobookSearchSession{Query: query, Results: results, CreatedAt: s.now()}
	s.mu.Lock()
	s.searches[userID] = sess
	s.mu.Unlock()
	return sess, nil
}

// GetSearch returns the user's latest search. The session is read-only.
func (s *AudiobookService) GetSearch(userID int64) (*AudiobookSearchSession, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	sess, ok := s.searches[userID]
	return sess, ok
}

// OpenSelection fetches the torrent of a search result and starts a file
// selection. Files within limits are preselected when they all fit.
func (s *AudiobookService) OpenSelection(ctx context.Context, userID int64, resultID string) (*AudiobookSelection, error) {
	if !s.Enabled() {
		return nil, domain.NewError(domain.ErrCodeFeatureDisabled, "audiobooks are disabled")
	}

	sess, ok := s.GetSearch(userID)
	if !ok {
		return nil, domain.NewError(domain.ErrCodeSessionExpired, "search session expired")
	}
	idx := slices.IndexFunc(sess.Results, func(a domain.Audiobook) bool { return a.ID == resultID })
	if idx < 0 {
		return nil, domain.NewError(domain.ErrCodeSessionExpired, "search result not found")
	}
	result := sess.Results[idx]

	data, err := s.searcher.FetchTorrent(ctx, result.TorrentURL)
	if err != nil {
		return nil, err
	}
	info, err := s.downloader.Parse(data)
	if err != nil {
		return nil, err
	}

	files := filterByExtension(info.Files, s.cfg.FileExtensions)
	if len(files) == 0 {
		return nil, domain.NewError(domain.ErrCodeNoAudioFiles, "no audio files in torrent")
	}
	slices.SortStableFunc(files, func(a, b domain.TorrentFile) int { return naturalCompare(a.Path, b.Path) })

	sel := &AudiobookSelection{
		ID:          newID(4),
		UserID:      userID,
		Title:       result.Title,
		InfoHash:    info.InfoHash,
		Files:       files,
		Selected:    make(map[int]bool),
		MaxFileSize: s.cfg.MaxFileSize,
		CreatedAt:   s.now(),
		torrent:     data,
	}
	sel.SelectAll()
	if s.cfg.MaxDownloadSize > 0 && sel.SelectedSize() > s.cfg.MaxDownloadSize {
		sel.SelectNone()
	}

	s.mu.Lock()
	// One open selection per user keeps memory bounded.
	for id, other := range s.selections {
		if other.UserID == userID {
			delete(s.selections, id)
		}
	}
	s.selections[sel.ID] = sel
	s.mu.Unlock()

	return sel.clone(), nil
}

// UpdateSelection applies fn to the user's selection and returns a copy.
func (s *AudiobookService) UpdateSelection(userID int64, selectionID string, fn func(*AudiobookSelection)) (*AudiobookSelection, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	sel, ok := s.selections[selectionID]
	if !ok || sel.UserID != userID {
		return nil, domain.NewError(domain.ErrCodeSessionExpired, "selection expired")
	}
	fn(sel)
	return sel.clone(), nil
}

// CloseSelection discards a selection.
func (s *AudiobookService) CloseSelection(userID int64, selectionID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if sel, ok := s.selections[selectionID]; ok && sel.UserID == userID {
		delete(s.selections, selectionID)
	}
}

// StartDownload turns a selection into a download job. All statuses, including
// the initial one, go through the notifier; statusMessageID is the message
// that shows them.
func (s *AudiobookService) StartDownload(ctx context.Context, userID, chatID int64, statusMessageID int, selectionID string) (domain.AudiobookJob, error) {
	if !s.Enabled() {
		return domain.AudiobookJob{}, domain.NewError(domain.ErrCodeFeatureDisabled, "audiobooks are disabled")
	}

	s.mu.Lock()
	sel, ok := s.selections[selectionID]
	if !ok || sel.UserID != userID {
		s.mu.Unlock()
		return domain.AudiobookJob{}, domain.NewError(domain.ErrCodeSessionExpired, "selection expired")
	}

	count, size := sel.SelectedCount(), sel.SelectedSize()
	if count == 0 {
		s.mu.Unlock()
		return domain.AudiobookJob{}, domain.NewError(domain.ErrCodeSelectionEmpty, "no files selected")
	}
	if s.cfg.MaxDownloadSize > 0 && size > s.cfg.MaxDownloadSize {
		s.mu.Unlock()
		return domain.AudiobookJob{}, domain.NewError(domain.ErrCodeSelectionTooLarge,
			fmt.Sprintf("selected %d bytes, limit %d", size, s.cfg.MaxDownloadSize))
	}

	var userJobs int
	for _, j := range s.jobs {
		if j.TelegramID == userID {
			userJobs++
		}
		if j.infoHash == sel.InfoHash {
			s.mu.Unlock()
			return domain.AudiobookJob{}, domain.NewError(domain.ErrCodeAlreadyDownloading, "torrent is already being downloaded")
		}
	}
	if userJobs >= s.cfg.MaxPerUser {
		s.mu.Unlock()
		return domain.AudiobookJob{}, domain.NewError(domain.ErrCodeUserLimitReached,
			fmt.Sprintf("limit of %d active downloads reached", s.cfg.MaxPerUser))
	}
	if used := s.usedBytesLocked(); s.cfg.StorageMaxSize > 0 && used+size > s.cfg.StorageMaxSize {
		s.mu.Unlock()
		return domain.AudiobookJob{}, domain.NewError(domain.ErrCodeQuotaExceeded,
			fmt.Sprintf("storage quota: used %d + %d > %d", used, size, s.cfg.StorageMaxSize))
	}

	jobCtx, cancel := context.WithCancel(s.ctx)
	job := &audiobookJob{
		AudiobookJob: domain.AudiobookJob{
			ID:              newID(16),
			TelegramID:      userID,
			ChatID:          chatID,
			StatusMessageID: statusMessageID,
			Title:           sel.Title,
			FileCount:       count,
			TotalSize:       size,
			CreatedAt:       s.now(),
		},
		torrent:  sel.torrent,
		indexes:  sel.SelectedIndexes(),
		infoHash: sel.InfoHash,
		ctx:      jobCtx,
		cancel:   cancel,
	}

	holding := s.sem.TryAcquire(1)
	if holding {
		job.Status = domain.AudiobookJobDownloading
		job.lastNotify = s.now()
	} else {
		job.Status = domain.AudiobookJobQueued
		job.WasQueued = true
		s.queue = append(s.queue, job.ID)
		job.QueuePosition = len(s.queue)
	}
	s.jobs[job.ID] = job
	delete(s.selections, selectionID)
	snap := job.AudiobookJob
	s.mu.Unlock()

	if err := s.repo.Save(ctx, &domain.AudiobookDownload{
		ID:              job.ID,
		TelegramID:      userID,
		ChatID:          chatID,
		StatusMessageID: statusMessageID,
		Title:           job.Title,
		Status:          job.Status,
		Size:            size,
		CreatedAt:       job.CreatedAt,
	}); err != nil {
		s.logger.Warn("failed to persist audiobook job", "job", job.ID, "error", err)
	}

	s.logger.Info("audiobook download requested", "job", job.ID, "user", userID, "title", job.Title,
		"files", count, "size", size, "queued", !holding)

	// The first status is delivered before the job goroutine exists, so later
	// events can't be overwritten by it.
	if holding {
		s.notify(domain.AudiobookEventStarted, snap)
	} else {
		s.notify(domain.AudiobookEventQueued, snap)
	}

	s.wg.Add(1)
	go s.run(job, holding)

	return snap, nil
}

// CancelJob stops a queued or running download. Admins may cancel any job.
func (s *AudiobookService) CancelJob(userID int64, jobID string, isAdmin bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	job, ok := s.jobs[jobID]
	if !ok || (job.TelegramID != userID && !isAdmin) {
		return domain.NewError(domain.ErrCodeNotFound, "job not found")
	}
	job.cancelledByUser = true
	job.cancel()
	return nil
}

// UserDownloads lists a user's active jobs and ready downloads.
func (s *AudiobookService) UserDownloads(userID int64) ([]domain.AudiobookJob, []domain.AudiobookDownload) {
	s.mu.Lock()
	defer s.mu.Unlock()

	var active []domain.AudiobookJob
	for _, j := range s.jobs {
		if j.TelegramID == userID {
			active = append(active, j.AudiobookJob)
		}
	}
	slices.SortFunc(active, func(a, b domain.AudiobookJob) int { return a.CreatedAt.Compare(b.CreatedAt) })

	now := s.now()
	var ready []domain.AudiobookDownload
	for _, d := range s.downloads {
		if d.TelegramID == userID && now.Before(d.ExpiresAt) {
			ready = append(ready, *d)
		}
	}
	slices.SortFunc(ready, func(a, b domain.AudiobookDownload) int { return a.CreatedAt.Compare(b.CreatedAt) })
	return active, ready
}

// Stats returns an overview for admins.
func (s *AudiobookService) Stats() AudiobookStats {
	s.mu.Lock()
	defer s.mu.Unlock()
	st := AudiobookStats{
		Queued:    len(s.queue),
		Ready:     len(s.downloads),
		UsedBytes: s.usedBytesLocked(),
		MaxBytes:  s.cfg.StorageMaxSize,
	}
	st.Active = len(s.jobs) - st.Queued
	return st
}

// GetDownload returns a ready, non-expired download (used by the web server).
func (s *AudiobookService) GetDownload(id string) (*domain.AudiobookDownload, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	d, ok := s.downloads[id]
	if !ok || !s.now().Before(d.ExpiresAt) {
		return nil, false
	}
	cp := *d
	return &cp, true
}

// LinkFor is the public URL of a download page.
func (s *AudiobookService) LinkFor(id string) string {
	return s.cfg.PublicURL + "/a/" + id + "/"
}

func (s *AudiobookService) run(job *audiobookJob, holding bool) {
	defer s.wg.Done()

	if !holding {
		if err := s.sem.Acquire(job.ctx, 1); err != nil {
			s.finish(job, nil, err)
			return
		}
		s.markStarted(job)
	}
	defer s.sem.Release(1)

	var (
		ctx    context.Context
		cancel context.CancelFunc
	)
	if s.cfg.DownloadTimeout > 0 {
		ctx, cancel = context.WithTimeout(job.ctx, s.cfg.DownloadTimeout)
	} else {
		ctx, cancel = context.WithCancel(job.ctx)
	}
	files, err := s.downloader.Download(ctx, job.ID, job.torrent, job.indexes, func(p domain.DownloadProgress) {
		s.onProgress(job, p)
	})
	if err != nil && errors.Is(ctx.Err(), context.DeadlineExceeded) && job.ctx.Err() == nil {
		err = domain.NewError(domain.ErrCodeTimeout, fmt.Sprintf("download timed out after %s", s.cfg.DownloadTimeout))
	}
	cancel()

	s.finish(job, files, err)
}

func (s *AudiobookService) markStarted(job *audiobookJob) {
	s.mu.Lock()
	job.Status = domain.AudiobookJobDownloading
	job.QueuePosition = 0
	job.lastNotify = s.now()
	s.removeFromQueueLocked(job.ID)
	snap := job.AudiobookJob
	queued := s.queuedSnapshotsLocked()
	s.mu.Unlock()

	s.logger.Info("audiobook download left the queue", "job", job.ID)
	s.notify(domain.AudiobookEventStarted, snap)
	for _, q := range queued {
		s.notify(domain.AudiobookEventQueued, q)
	}
}

func (s *AudiobookService) onProgress(job *audiobookJob, p domain.DownloadProgress) {
	s.mu.Lock()
	job.Progress = p
	now := s.now()
	if now.Sub(job.lastNotify) < s.cfg.ProgressInterval || p.Completed >= p.Total {
		s.mu.Unlock()
		return
	}
	job.lastNotify = now
	snap := job.AudiobookJob
	s.mu.Unlock()

	s.notify(domain.AudiobookEventProgress, snap)
}

func (s *AudiobookService) finish(job *audiobookJob, files []domain.StoredFile, err error) {
	shuttingDown := s.ctx.Err() != nil

	s.mu.Lock()
	delete(s.jobs, job.ID)
	wasQueued := s.removeFromQueueLocked(job.ID)
	var queued []domain.AudiobookJob
	if wasQueued {
		queued = s.queuedSnapshotsLocked()
	}

	var (
		kind     domain.AudiobookEventKind
		download *domain.AudiobookDownload
	)
	switch {
	case shuttingDown:
		// Keep the DB record: the owner is notified on the next start.
	case err == nil:
		kind = domain.AudiobookEventCompleted
		job.Status = domain.AudiobookJobCompleted
		job.ExpiresAt = s.now().Add(s.cfg.LinkTTL)
		job.Progress.Completed = job.TotalSize
		job.Progress.Total = job.TotalSize
		var size int64
		for _, f := range files {
			size += f.Size
		}
		download = &domain.AudiobookDownload{
			ID:              job.ID,
			TelegramID:      job.TelegramID,
			ChatID:          job.ChatID,
			StatusMessageID: job.StatusMessageID,
			Title:           job.Title,
			Status:          domain.AudiobookJobCompleted,
			Files:           files,
			Size:            size,
			CreatedAt:       job.CreatedAt,
			ExpiresAt:       job.ExpiresAt,
		}
		s.downloads[job.ID] = download
	case job.cancelledByUser:
		kind = domain.AudiobookEventCancelled
		job.Status = domain.AudiobookJobCancelled
	default:
		kind = domain.AudiobookEventFailed
		job.Status = domain.AudiobookJobFailed
		job.Err = err
	}
	snap := job.AudiobookJob
	s.mu.Unlock()
	job.cancel()

	if shuttingDown {
		return
	}

	ctx := context.Background()
	if download != nil {
		if err := s.repo.Save(ctx, download); err != nil {
			s.logger.Error("failed to persist audiobook download", "job", job.ID, "error", err)
		}
		s.logger.Info("audiobook download ready", "job", job.ID, "files", len(files), "size", download.Size)
	} else {
		s.dropDownload(ctx, job.ID)
		s.logger.Info("audiobook download finished without result", "job", job.ID, "status", job.Status, "error", err)
	}

	s.notify(kind, snap)
	for _, q := range queued {
		s.notify(domain.AudiobookEventQueued, q)
	}
}

func (s *AudiobookService) cleanupLoop() {
	defer s.wg.Done()
	ticker := time.NewTicker(s.cfg.CleanupInterval)
	defer ticker.Stop()
	for {
		select {
		case <-s.ctx.Done():
			return
		case <-ticker.C:
			s.cleanup()
		}
	}
}

// cleanup removes expired downloads and stale sessions.
func (s *AudiobookService) cleanup() {
	now := s.now()

	s.mu.Lock()
	var expired []string
	for id, d := range s.downloads {
		if !now.Before(d.ExpiresAt) {
			expired = append(expired, id)
			delete(s.downloads, id)
		}
	}
	for id, sess := range s.searches {
		if now.Sub(sess.CreatedAt) > audiobookSessionTTL {
			delete(s.searches, id)
		}
	}
	for id, sel := range s.selections {
		if now.Sub(sel.CreatedAt) > audiobookSessionTTL {
			delete(s.selections, id)
		}
	}
	s.mu.Unlock()

	for _, id := range expired {
		s.dropDownload(context.Background(), id)
		s.logger.Info("audiobook download expired", "job", id)
	}
}

func (s *AudiobookService) dropDownload(ctx context.Context, id string) {
	if err := s.downloader.Remove(id); err != nil {
		s.logger.Warn("failed to remove audiobook files", "job", id, "error", err)
	}
	if err := s.repo.Delete(ctx, id); err != nil {
		s.logger.Warn("failed to delete audiobook record", "job", id, "error", err)
	}
}

func (s *AudiobookService) usedBytesLocked() int64 {
	var used int64
	for _, d := range s.downloads {
		used += d.Size
	}
	for _, j := range s.jobs {
		used += j.TotalSize
	}
	return used
}

func (s *AudiobookService) removeFromQueueLocked(id string) bool {
	i := slices.Index(s.queue, id)
	if i < 0 {
		return false
	}
	s.queue = slices.Delete(s.queue, i, i+1)
	return true
}

func (s *AudiobookService) queuedSnapshotsLocked() []domain.AudiobookJob {
	out := make([]domain.AudiobookJob, 0, len(s.queue))
	for i, id := range s.queue {
		j := s.jobs[id]
		j.QueuePosition = i + 1
		out = append(out, j.AudiobookJob)
	}
	return out
}

func (s *AudiobookService) notify(kind domain.AudiobookEventKind, job domain.AudiobookJob) {
	if s.notifier != nil {
		s.notifier.OnAudiobookEvent(kind, job)
	}
}

func filterByExtension(files []domain.TorrentFile, exts []string) []domain.TorrentFile {
	if len(exts) == 0 || slices.Contains(exts, "*") {
		return slices.Clone(files)
	}
	out := make([]domain.TorrentFile, 0, len(files))
	for _, f := range files {
		ext := strings.ToLower(strings.TrimPrefix(path.Ext(f.Path), "."))
		if slices.Contains(exts, ext) {
			out = append(out, f)
		}
	}
	return out
}

func newID(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
