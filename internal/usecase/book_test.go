package usecase

import (
	"context"
	"errors"
	"io"
	"testing"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
)

type fakeProvider struct {
	name        string
	results     []domain.SearchResult
	err         error
	downloadErr error
}

func (p *fakeProvider) Name() string { return p.name }

func (p *fakeProvider) Search(ctx context.Context, query string, limit int) ([]domain.SearchResult, error) {
	return p.results, p.err
}

func (p *fakeProvider) Download(ctx context.Context, result domain.SearchResult, format domain.Format) (io.ReadCloser, string, error) {
	if p.downloadErr != nil {
		return nil, "", p.downloadErr
	}
	return nil, "", errors.New("not implemented")
}

type fakeSettingsRepo struct{}

func (f *fakeSettingsRepo) Get(ctx context.Context, telegramID int64) (*domain.UserSettings, error) {
	return &domain.UserSettings{TelegramID: telegramID}, nil
}

func (f *fakeSettingsRepo) Save(ctx context.Context, settings *domain.UserSettings) error {
	return nil
}

type fakeSearchCache struct {
	saved  *domain.SearchSession
	result *domain.SearchResult
}

func (f *fakeSearchCache) Save(ctx context.Context, session *domain.SearchSession) error {
	f.saved = session
	return nil
}

func (f *fakeSearchCache) Get(ctx context.Context, telegramID int64) (*domain.SearchSession, error) {
	return f.saved, nil
}

func (f *fakeSearchCache) FindResult(ctx context.Context, telegramID int64, resultID string) (*domain.SearchResult, error) {
	return f.result, nil
}

func (f *fakeSearchCache) DeleteExpired(ctx context.Context, ttl time.Duration) error {
	return nil
}

func newTestLogger() *log.Logger {
	return log.New(io.Discard)
}

func TestSearch_AllProvidersFail_ReturnsSourceUnavailableWithProviderName(t *testing.T) {
	providers := []domain.BookProvider{
		&fakeProvider{name: "Flibusta", err: errors.New("connection refused")},
	}
	svc := NewBookService(providers, &fakeSettingsRepo{}, &fakeSearchCache{}, newTestLogger())

	_, err := svc.Search(context.Background(), 1, "query")

	var de *domain.DomainError
	if !errors.As(err, &de) {
		t.Fatalf("expected *domain.DomainError, got %T (%v)", err, err)
	}
	if de.Code != domain.ErrCodeSourceUnavailable {
		t.Errorf("expected code %q, got %q", domain.ErrCodeSourceUnavailable, de.Code)
	}
	if de.Message != "Flibusta" {
		t.Errorf("expected message to name the failed provider %q, got %q", "Flibusta", de.Message)
	}
}

func TestSearch_ProvidersReturnZeroResultsWithoutError_ReturnsNotFound(t *testing.T) {
	providers := []domain.BookProvider{
		&fakeProvider{name: "Flibusta"},
	}
	svc := NewBookService(providers, &fakeSettingsRepo{}, &fakeSearchCache{}, newTestLogger())

	_, err := svc.Search(context.Background(), 1, "query")

	var de *domain.DomainError
	if !errors.As(err, &de) {
		t.Fatalf("expected *domain.DomainError, got %T (%v)", err, err)
	}
	if de.Code != domain.ErrCodeNotFound {
		t.Errorf("expected code %q, got %q", domain.ErrCodeNotFound, de.Code)
	}
}

func TestSearch_OneProviderFailsButAnotherSucceeds_ReturnsResults(t *testing.T) {
	providers := []domain.BookProvider{
		&fakeProvider{name: "Flibusta", err: errors.New("connection refused")},
		&fakeProvider{name: "RoyalLib", results: []domain.SearchResult{
			{ID: "1", Book: domain.Book{Title: "Some Book", Provider: "RoyalLib"}},
		}},
	}
	svc := NewBookService(providers, &fakeSettingsRepo{}, &fakeSearchCache{}, newTestLogger())

	results, err := svc.Search(context.Background(), 1, "query")

	if err != nil {
		t.Fatalf("expected no error, got %v", err)
	}
	if len(results) != 1 {
		t.Fatalf("expected 1 result, got %d", len(results))
	}
}

func TestDownloadWithFormat_ProviderDownloadFails_ReturnsSourceUnavailableWithProviderName(t *testing.T) {
	provider := &fakeProvider{name: "Flibusta", downloadErr: errors.New("connection refused")}
	providers := []domain.BookProvider{provider}

	book := domain.Book{Title: "Some Book", Provider: "Flibusta", Formats: []domain.Format{domain.FormatFB2}}
	result := domain.NewSearchResult(book)
	cache := &fakeSearchCache{result: &result}

	svc := NewBookService(providers, &fakeSettingsRepo{}, cache, newTestLogger())

	_, _, _, err := svc.DownloadWithFormat(context.Background(), 1, result.ID, domain.FormatFB2)

	var de *domain.DomainError
	if !errors.As(err, &de) {
		t.Fatalf("expected *domain.DomainError, got %T (%v)", err, err)
	}
	if de.Code != domain.ErrCodeSourceUnavailable {
		t.Errorf("expected code %q, got %q", domain.ErrCodeSourceUnavailable, de.Code)
	}
	if de.Message != "Flibusta" {
		t.Errorf("expected message to name the failed provider %q, got %q", "Flibusta", de.Message)
	}
}
