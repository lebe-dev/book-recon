package usecase

import (
	"context"
	"io"
	"sync"
	"testing"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

type fakeABSearcher struct {
	results []domain.Audiobook
}

func (f *fakeABSearcher) Search(ctx context.Context, query string, limit int) ([]domain.Audiobook, error) {
	return f.results, nil
}

func (f *fakeABSearcher) FetchTorrent(ctx context.Context, url string) ([]byte, error) {
	return []byte(url), nil
}

type fakeABDownloader struct {
	mu      sync.Mutex
	infos   map[string]*domain.TorrentInfo // keyed by torrent data (= URL in fakes)
	release chan error                     // each Download waits for one value
	removed []string
	orphans map[string]bool
}

func (f *fakeABDownloader) Parse(data []byte) (*domain.TorrentInfo, error) {
	return f.infos[string(data)], nil
}

func (f *fakeABDownloader) Download(ctx context.Context, jobID string, data []byte, indexes []int, onProgress func(domain.DownloadProgress)) ([]domain.StoredFile, error) {
	onProgress(domain.DownloadProgress{Completed: 1, Total: 2})
	select {
	case err := <-f.release:
		if err != nil {
			return nil, err
		}
	case <-ctx.Done():
		return nil, ctx.Err()
	}
	info := f.infos[string(data)]
	var out []domain.StoredFile
	for _, idx := range indexes {
		for _, tf := range info.Files {
			if tf.Index == idx {
				out = append(out, domain.StoredFile{Path: tf.Path, Size: tf.Size})
			}
		}
	}
	return out, nil
}

func (f *fakeABDownloader) Remove(jobID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.removed = append(f.removed, jobID)
	return nil
}

func (f *fakeABDownloader) RemoveOrphans(keep map[string]bool) error {
	f.orphans = keep
	return nil
}

type memABRepo struct {
	mu   sync.Mutex
	rows map[string]domain.AudiobookDownload
}

func (r *memABRepo) Save(ctx context.Context, d *domain.AudiobookDownload) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.rows[d.ID] = *d
	return nil
}

func (r *memABRepo) List(ctx context.Context) ([]domain.AudiobookDownload, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []domain.AudiobookDownload
	for _, d := range r.rows {
		out = append(out, d)
	}
	return out, nil
}

func (r *memABRepo) Delete(ctx context.Context, id string) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	delete(r.rows, id)
	return nil
}

func (r *memABRepo) get(id string) (domain.AudiobookDownload, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	d, ok := r.rows[id]
	return d, ok
}

type memSettings struct {
	values map[string]bool
}

func (m *memSettings) GetBool(ctx context.Context, key string) (bool, bool, error) {
	v, ok := m.values[key]
	return v, ok, nil
}

func (m *memSettings) SetBool(ctx context.Context, key string, value bool) error {
	m.values[key] = value
	return nil
}

type abEvent struct {
	kind domain.AudiobookEventKind
	job  domain.AudiobookJob
}

type chanNotifier chan abEvent

func (c chanNotifier) OnAudiobookEvent(kind domain.AudiobookEventKind, job domain.AudiobookJob) {
	c <- abEvent{kind, job}
}

type abFixture struct {
	svc      *AudiobookService
	dl       *fakeABDownloader
	repo     *memABRepo
	settings *memSettings
	events   chanNotifier
}

func newABFixture(t *testing.T, cfg AudiobookConfig) *abFixture {
	t.Helper()
	searcher := &fakeABSearcher{results: []domain.Audiobook{
		domain.NewAudiobook(domain.Audiobook{Title: "Book A", TorrentURL: "a"}),
		domain.NewAudiobook(domain.Audiobook{Title: "Book B", TorrentURL: "b"}),
	}}
	dl := &fakeABDownloader{
		release: make(chan error, 10),
		infos: map[string]*domain.TorrentInfo{
			"a": {InfoHash: "ha", Files: []domain.TorrentFile{
				{Index: 0, Path: "cd/Глава 10.mp3", Size: 100},
				{Index: 1, Path: "cd/Глава 2.mp3", Size: 100},
				{Index: 2, Path: "cover.jpg", Size: 5},
				{Index: 3, Path: "bonus/huge.m4b", Size: 1000},
			}},
			"b": {InfoHash: "hb", Files: []domain.TorrentFile{
				{Index: 0, Path: "01.mp3", Size: 50},
			}},
		},
	}
	if cfg.FileExtensions == nil {
		cfg.FileExtensions = []string{"mp3", ".M4B"}
	}
	if cfg.LinkTTL == 0 {
		cfg.LinkTTL = time.Hour
	}
	if cfg.ProgressInterval == 0 {
		cfg.ProgressInterval = time.Hour
	}
	cfg.PublicURL = "https://audio.example.com"

	f := &abFixture{
		dl:       dl,
		repo:     &memABRepo{rows: map[string]domain.AudiobookDownload{}},
		settings: &memSettings{values: map[string]bool{}},
		events:   make(chanNotifier, 100),
	}
	f.svc = NewAudiobookService(searcher, dl, f.repo, f.settings, cfg, log.New(io.Discard))
	f.svc.SetNotifier(f.events)
	if err := f.svc.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(f.svc.Close)
	return f
}

func (f *abFixture) next(t *testing.T) abEvent {
	t.Helper()
	select {
	case e := <-f.events:
		return e
	case <-time.After(5 * time.Second):
		t.Fatal("timeout waiting for event")
		return abEvent{}
	}
}

func (f *abFixture) open(t *testing.T, userID int64, title string) *AudiobookSelection {
	t.Helper()
	sess, err := f.svc.Search(context.Background(), userID, "q")
	if err != nil {
		t.Fatal(err)
	}
	for _, r := range sess.Results {
		if r.Title == title {
			sel, err := f.svc.OpenSelection(context.Background(), userID, r.ID)
			if err != nil {
				t.Fatal(err)
			}
			return sel
		}
	}
	t.Fatalf("result %q not found", title)
	return nil
}

func errCode(err error) domain.ErrorCode {
	c, _ := domain.ErrorCodeFrom(err)
	return c
}

func TestAudiobookSelectionFilterSortPreselect(t *testing.T) {
	f := newABFixture(t, AudiobookConfig{MaxFileSize: 500, MaxConcurrent: 1, MaxPerUser: 1})
	sel := f.open(t, 1, "Book A")

	var paths []string
	for _, file := range sel.Files {
		paths = append(paths, file.Path)
	}
	want := []string{"bonus/huge.m4b", "cd/Глава 2.mp3", "cd/Глава 10.mp3"}
	if len(paths) != len(want) {
		t.Fatalf("files = %v, want %v", paths, want)
	}
	for i := range want {
		if paths[i] != want[i] {
			t.Fatalf("files = %v, want %v", paths, want)
		}
	}

	// huge.m4b exceeds MaxFileSize: not preselected and can't be toggled on.
	if sel.SelectedCount() != 2 || sel.SelectedSize() != 200 {
		t.Errorf("preselected %d files / %d bytes", sel.SelectedCount(), sel.SelectedSize())
	}
	sel, _ = f.svc.UpdateSelection(1, sel.ID, func(s *AudiobookSelection) { s.ToggleFile(0) })
	if sel.Selected[3] {
		t.Error("oversized file got selected")
	}

	folders := sel.Folders()
	if len(folders) != 2 || folders[1].Path != "cd" {
		t.Fatalf("folders = %+v", folders)
	}
	sel, _ = f.svc.UpdateSelection(1, sel.ID, func(s *AudiobookSelection) { s.ToggleFolder(1) })
	if sel.SelectedCount() != 0 {
		t.Errorf("folder toggle off: %d selected", sel.SelectedCount())
	}
	sel, _ = f.svc.UpdateSelection(1, sel.ID, func(s *AudiobookSelection) { s.ToggleFolder(1) })
	if sel.SelectedCount() != 2 {
		t.Errorf("folder toggle on: %d selected", sel.SelectedCount())
	}

	if _, err := f.svc.UpdateSelection(2, sel.ID, func(*AudiobookSelection) {}); errCode(err) != domain.ErrCodeSessionExpired {
		t.Errorf("other user's selection: err = %v", err)
	}
}

func TestAudiobookPreselectNoneWhenTooLarge(t *testing.T) {
	f := newABFixture(t, AudiobookConfig{MaxDownloadSize: 150, MaxConcurrent: 1, MaxPerUser: 1})
	sel := f.open(t, 1, "Book A")
	if sel.SelectedCount() != 0 {
		t.Errorf("expected empty preselection, got %d", sel.SelectedCount())
	}

	_, err := f.svc.StartDownload(context.Background(), 1, 1, 10, sel.ID)
	if errCode(err) != domain.ErrCodeSelectionEmpty {
		t.Errorf("empty selection: err = %v", err)
	}

	if _, err := f.svc.UpdateSelection(1, sel.ID, func(s *AudiobookSelection) { s.SelectAll() }); err != nil {
		t.Fatal(err)
	}
	_, err = f.svc.StartDownload(context.Background(), 1, 1, 10, sel.ID)
	if errCode(err) != domain.ErrCodeSelectionTooLarge {
		t.Errorf("too large: err = %v", err)
	}
}

func TestAudiobookDownloadCompletes(t *testing.T) {
	f := newABFixture(t, AudiobookConfig{MaxConcurrent: 1, MaxPerUser: 1, MaxFileSize: 500})
	sel := f.open(t, 1, "Book A")

	job, err := f.svc.StartDownload(context.Background(), 1, 100, 42, sel.ID)
	if err != nil {
		t.Fatal(err)
	}
	if job.Status != domain.AudiobookJobDownloading || job.WasQueued || job.StatusMessageID != 42 {
		t.Errorf("job = %+v", job)
	}
	if _, ok := f.repo.get(job.ID); !ok {
		t.Error("job not persisted")
	}
	if e := f.next(t); e.kind != domain.AudiobookEventStarted || e.job.ID != job.ID {
		t.Fatalf("initial event = %+v", e)
	}

	f.dl.release <- nil
	e := f.next(t)
	if e.kind != domain.AudiobookEventCompleted || e.job.ExpiresAt.IsZero() {
		t.Fatalf("event = %+v", e)
	}

	d, ok := f.svc.GetDownload(job.ID)
	if !ok || len(d.Files) != 2 || d.Size != 200 {
		t.Fatalf("GetDownload = %+v, %v", d, ok)
	}
	if row, _ := f.repo.get(job.ID); row.Status != domain.AudiobookJobCompleted || len(row.Files) != 2 {
		t.Errorf("persisted row = %+v", row)
	}
	if link := f.svc.LinkFor(job.ID); link != "https://audio.example.com/a/"+job.ID+"/" {
		t.Errorf("link = %s", link)
	}
	if _, err := f.svc.UpdateSelection(1, sel.ID, func(*AudiobookSelection) {}); err == nil {
		t.Error("selection should be consumed by StartDownload")
	}
}

func TestAudiobookQueueAndLimits(t *testing.T) {
	f := newABFixture(t, AudiobookConfig{MaxConcurrent: 1, MaxPerUser: 1, MaxFileSize: 500})

	first, err := f.svc.StartDownload(context.Background(), 1, 1, 1, f.open(t, 1, "Book A").ID)
	if err != nil {
		t.Fatal(err)
	}
	f.next(t) // started

	// Same user: per-user limit.
	_, err = f.svc.StartDownload(context.Background(), 1, 1, 1, f.open(t, 1, "Book B").ID)
	if errCode(err) != domain.ErrCodeUserLimitReached {
		t.Errorf("per-user limit: err = %v", err)
	}

	// Other user, same torrent.
	_, err = f.svc.StartDownload(context.Background(), 2, 2, 2, f.open(t, 2, "Book A").ID)
	if errCode(err) != domain.ErrCodeAlreadyDownloading {
		t.Errorf("same torrent: err = %v", err)
	}

	// Other user, other torrent: queued behind the first.
	second, err := f.svc.StartDownload(context.Background(), 2, 2, 2, f.open(t, 2, "Book B").ID)
	if err != nil {
		t.Fatal(err)
	}
	if second.Status != domain.AudiobookJobQueued || second.QueuePosition != 1 {
		t.Errorf("second = %+v", second)
	}
	if e := f.next(t); e.kind != domain.AudiobookEventQueued || e.job.QueuePosition != 1 {
		t.Fatalf("event = %+v", e)
	}
	if st := f.svc.Stats(); st.Active != 1 || st.Queued != 1 || st.UsedBytes != 250 {
		t.Errorf("stats = %+v", st)
	}

	f.dl.release <- nil
	if e := f.next(t); e.kind != domain.AudiobookEventCompleted || e.job.ID != first.ID {
		t.Fatalf("event = %+v", e)
	}
	if e := f.next(t); e.kind != domain.AudiobookEventStarted || e.job.ID != second.ID || !e.job.WasQueued {
		t.Fatalf("event = %+v", e)
	}

	f.dl.release <- nil
	if e := f.next(t); e.kind != domain.AudiobookEventCompleted || e.job.ID != second.ID {
		t.Fatalf("event = %+v", e)
	}
	active, ready := f.svc.UserDownloads(2)
	if len(active) != 0 || len(ready) != 1 {
		t.Errorf("user downloads: active=%d ready=%d", len(active), len(ready))
	}
}

func TestAudiobookQuota(t *testing.T) {
	f := newABFixture(t, AudiobookConfig{MaxConcurrent: 2, MaxPerUser: 2, StorageMaxSize: 220, MaxFileSize: 500})
	if _, err := f.svc.StartDownload(context.Background(), 1, 1, 1, f.open(t, 1, "Book A").ID); err != nil {
		t.Fatal(err)
	}
	_, err := f.svc.StartDownload(context.Background(), 1, 1, 1, f.open(t, 1, "Book B").ID)
	if errCode(err) != domain.ErrCodeQuotaExceeded {
		t.Errorf("quota: err = %v", err)
	}
}

func TestAudiobookCancelAndFailure(t *testing.T) {
	f := newABFixture(t, AudiobookConfig{MaxConcurrent: 1, MaxPerUser: 1, MaxFileSize: 500})
	job, err := f.svc.StartDownload(context.Background(), 1, 1, 1, f.open(t, 1, "Book A").ID)
	if err != nil {
		t.Fatal(err)
	}

	f.next(t) // started
	if err := f.svc.CancelJob(2, job.ID, false); err == nil {
		t.Error("other user cancelled the job")
	}
	if err := f.svc.CancelJob(1, job.ID, false); err != nil {
		t.Fatal(err)
	}
	if e := f.next(t); e.kind != domain.AudiobookEventCancelled {
		t.Fatalf("event = %+v", e)
	}
	if _, ok := f.repo.get(job.ID); ok {
		t.Error("cancelled job still persisted")
	}

	job, err = f.svc.StartDownload(context.Background(), 1, 1, 1, f.open(t, 1, "Book B").ID)
	if err != nil {
		t.Fatal(err)
	}
	f.next(t) // started
	f.dl.release <- domain.NewError(domain.ErrCodeStalled, "stalled")
	e := f.next(t)
	if e.kind != domain.AudiobookEventFailed || errCode(e.job.Err) != domain.ErrCodeStalled {
		t.Fatalf("event = %+v", e)
	}
	f.dl.mu.Lock()
	removed := len(f.dl.removed)
	f.dl.mu.Unlock()
	if removed != 2 {
		t.Errorf("Remove called %d times, want 2", removed)
	}
}

func TestAudiobookDisabled(t *testing.T) {
	f := newABFixture(t, AudiobookConfig{})
	if err := f.svc.SetEnabled(context.Background(), false); err != nil {
		t.Fatal(err)
	}
	if _, err := f.svc.Search(context.Background(), 1, "q"); errCode(err) != domain.ErrCodeFeatureDisabled {
		t.Errorf("err = %v", err)
	}
	if v := f.settings.values[settingAudiobooksEnabled]; v {
		t.Error("setting not persisted")
	}
}

func TestAudiobookStartRestoresState(t *testing.T) {
	now := time.Now()
	repo := &memABRepo{rows: map[string]domain.AudiobookDownload{
		"ready":       {ID: "ready", Status: domain.AudiobookJobCompleted, ExpiresAt: now.Add(time.Hour), Size: 10},
		"expired":     {ID: "expired", Status: domain.AudiobookJobCompleted, ExpiresAt: now.Add(-time.Minute)},
		"interrupted": {ID: "interrupted", Status: domain.AudiobookJobDownloading, ChatID: 7, StatusMessageID: 8},
	}}
	settings := &memSettings{values: map[string]bool{settingAudiobooksEnabled: false}}
	dl := &fakeABDownloader{}
	events := make(chanNotifier, 10)

	svc := NewAudiobookService(&fakeABSearcher{}, dl, repo, settings, AudiobookConfig{}, log.New(io.Discard))
	svc.SetNotifier(events)
	if err := svc.Start(context.Background()); err != nil {
		t.Fatal(err)
	}
	defer svc.Close()

	if svc.Enabled() {
		t.Error("runtime switch not restored")
	}
	if _, ok := svc.GetDownload("ready"); !ok {
		t.Error("ready download not restored")
	}
	if _, ok := repo.get("expired"); ok {
		t.Error("expired record not deleted")
	}
	if _, ok := repo.get("interrupted"); ok {
		t.Error("interrupted record not deleted")
	}
	if len(dl.orphans) != 1 || !dl.orphans["ready"] {
		t.Errorf("RemoveOrphans keep = %v", dl.orphans)
	}
	e := <-events
	if e.kind != domain.AudiobookEventInterrupted || e.job.ChatID != 7 || e.job.StatusMessageID != 8 {
		t.Errorf("event = %+v", e)
	}
}

func TestAudiobookCleanupExpires(t *testing.T) {
	f := newABFixture(t, AudiobookConfig{MaxConcurrent: 1, MaxPerUser: 1, MaxFileSize: 500})
	job, err := f.svc.StartDownload(context.Background(), 1, 1, 1, f.open(t, 1, "Book B").ID)
	if err != nil {
		t.Fatal(err)
	}
	f.next(t) // started
	f.dl.release <- nil
	f.next(t) // completed

	f.svc.now = func() time.Time { return time.Now().Add(2 * time.Hour) }
	if _, ok := f.svc.GetDownload(job.ID); ok {
		t.Error("expired download still served")
	}
	f.svc.cleanup()
	if _, ok := f.repo.get(job.ID); ok {
		t.Error("expired record not deleted")
	}
	if _, ok := f.svc.GetSearch(1); ok {
		t.Error("stale search session not removed")
	}
}

func TestFilterByExtensionWildcard(t *testing.T) {
	files := []domain.TorrentFile{{Path: "a.mp3"}, {Path: "b.jpg"}}
	if got := filterByExtension(files, []string{"*"}); len(got) != 2 {
		t.Errorf("wildcard kept %d files", len(got))
	}
	if got := filterByExtension(files, []string{"mp3"}); len(got) != 1 || got[0].Path != "a.mp3" {
		t.Errorf("mp3 filter = %+v", got)
	}
}

func TestNaturalCompare(t *testing.T) {
	tests := []struct {
		a, b string
		want int
	}{
		{"Глава 2", "Глава 10", -1},
		{"10", "9", 1},
		{"a01", "a1", 0},
		{"cd1/02.mp3", "cd1/10.mp3", -1},
		{"abc", "abd", -1},
		{"ab", "abc", -1},
	}
	for _, tt := range tests {
		if got := naturalCompare(tt.a, tt.b); got != tt.want {
			t.Errorf("naturalCompare(%q, %q) = %d, want %d", tt.a, tt.b, got, tt.want)
		}
	}
}
