package usecase

import (
	"context"
	"io"
	"os"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/domain"
	"golang.org/x/sync/errgroup"
)

const (
	MaxFileSize     = 50 * 1024 * 1024 // 50 MB (Telegram Bot API limit)
	MaxQueryLength  = 200
	SearchLimit     = 20
	PageSize        = 5
	SessionTTL      = 30 * time.Minute
	DownloadTimeout = 60 * time.Second
	ProviderTimeout = 15 * time.Second
)

type BookService struct {
	providers   []domain.BookProvider
	providerMap map[string]domain.BookProvider
	settings    domain.UserSettingsRepository
	searchCache domain.SearchCacheRepository
	logger      *log.Logger
}

func NewBookService(
	providers []domain.BookProvider,
	settings domain.UserSettingsRepository,
	searchCache domain.SearchCacheRepository,
	logger *log.Logger,
) *BookService {
	pm := make(map[string]domain.BookProvider, len(providers))
	for _, p := range providers {
		pm[p.Name()] = p
	}
	return &BookService{
		providers:   providers,
		providerMap: pm,
		settings:    settings,
		searchCache: searchCache,
		logger:      logger,
	}
}

// Search queries all providers in parallel, caches results, returns first page.
func (s *BookService) Search(ctx context.Context, telegramID int64, query string) ([]domain.SearchResult, error) {
	if len(query) > MaxQueryLength {
		query = query[:MaxQueryLength]
	}

	type providerResult struct {
		results []domain.SearchResult
		name    string
	}

	ch := make(chan providerResult, len(s.providers))
	g, gctx := errgroup.WithContext(ctx)

	for _, p := range s.providers {
		g.Go(func() error {
			pctx, cancel := context.WithTimeout(gctx, ProviderTimeout)
			defer cancel()

			results, err := p.Search(pctx, query, SearchLimit)
			if err != nil {
				s.logger.Warn("provider search failed", "provider", p.Name(), "error", err)
				ch <- providerResult{name: p.Name()}
				return nil // partial results: don't fail the whole search
			}

			if len(results) == 0 {
				s.logger.Warn("provider returned 0 results", "provider", p.Name(), "query", query)
			}

			ch <- providerResult{results: results, name: p.Name()}
			return nil
		})
	}

	_ = g.Wait()
	close(ch)

	var allResults []domain.SearchResult
	for pr := range ch {
		allResults = append(allResults, pr.results...)
	}

	if len(allResults) == 0 {
		return nil, domain.NewError(domain.ErrCodeNotFound, "no results found")
	}

	if len(allResults) > SearchLimit {
		allResults = allResults[:SearchLimit]
	}

	session := &domain.SearchSession{
		TelegramID: telegramID,
		Results:    allResults,
		CreatedAt:  time.Now().UTC(),
	}
	if err := s.searchCache.Save(ctx, session); err != nil {
		s.logger.Error("failed to cache search results", "error", err)
		return nil, domain.WrapError(domain.ErrCodeProviderError, "failed to cache results", err)
	}

	end := PageSize
	if end > len(allResults) {
		end = len(allResults)
	}
	return allResults[:end], nil
}

// GetPage returns a slice of results for pagination.
func (s *BookService) GetPage(ctx context.Context, telegramID int64, offset int) (results []domain.SearchResult, hasMore bool, err error) {
	session, err := s.searchCache.Get(ctx, telegramID)
	if err != nil {
		return nil, false, err
	}
	if session == nil {
		return nil, false, domain.NewError(domain.ErrCodeNotFound, "no search session found")
	}

	total := len(session.Results)
	if offset >= total {
		return nil, false, nil
	}

	end := offset + PageSize
	if end > total {
		end = total
	}

	return session.Results[offset:end], end < total, nil
}

// Download fetches a book to a temp file, checking size limits.
// Returns path to the temp file and the filename for sending.
// The caller is responsible for removing the temp file.
func (s *BookService) Download(ctx context.Context, telegramID int64, resultID string) (string, string, error) {
	result, err := s.searchCache.FindResult(ctx, telegramID, resultID)
	if err != nil {
		return "", "", err
	}
	if result == nil {
		return "", "", domain.NewError(domain.ErrCodeNotFound, "result not found in cache")
	}

	userSettings, err := s.GetSettings(ctx, telegramID)
	if err != nil {
		return "", "", err
	}

	format := userSettings.PreferredFormat
	if !result.Book.HasFormat(format) {
		if len(result.Book.Formats) == 0 {
			return "", "", domain.NewError(domain.ErrCodeFormatNA, "no formats available")
		}
		format = result.Book.Formats[0]
	}

	provider, ok := s.providerMap[result.Book.Provider]
	if !ok {
		return "", "", domain.NewError(domain.ErrCodeProviderError, "unknown provider: "+result.Book.Provider)
	}

	dlCtx, cancel := context.WithTimeout(ctx, DownloadTimeout)
	defer cancel()

	reader, filename, err := provider.Download(dlCtx, *result, format)
	if err != nil {
		return "", "", domain.WrapError(domain.ErrCodeProviderError, "download failed", err)
	}
	defer func() { _ = reader.Close() }()

	tmpFile, err := os.CreateTemp("", "book-recon-*."+string(format))
	if err != nil {
		return "", "", domain.WrapError(domain.ErrCodeProviderError, "failed to create temp file", err)
	}

	limited := io.LimitReader(reader, MaxFileSize+1)
	written, err := io.Copy(tmpFile, limited)
	_ = tmpFile.Close()

	if err != nil {
		_ = os.Remove(tmpFile.Name())
		return "", "", domain.WrapError(domain.ErrCodeProviderError, "failed to write temp file", err)
	}

	if written > MaxFileSize {
		_ = os.Remove(tmpFile.Name())
		return "", "", domain.NewError(domain.ErrCodeFileTooLarge, "file exceeds 50 MB limit")
	}

	return tmpFile.Name(), filename, nil
}

// GetSettings returns user settings (or defaults).
func (s *BookService) GetSettings(ctx context.Context, telegramID int64) (*domain.UserSettings, error) {
	settings, err := s.settings.Get(ctx, telegramID)
	if err != nil {
		return nil, err
	}
	if settings == nil {
		return &domain.UserSettings{
			TelegramID:      telegramID,
			PreferredFormat: domain.FormatEPUB,
		}, nil
	}
	return settings, nil
}

// SetFormat sets the user's preferred format.
func (s *BookService) SetFormat(ctx context.Context, telegramID int64, format domain.Format) error {
	settings, err := s.GetSettings(ctx, telegramID)
	if err != nil {
		return err
	}
	settings.PreferredFormat = format
	return s.settings.Save(ctx, settings)
}
