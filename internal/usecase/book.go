package usecase

import (
	"context"
	"io"
	"os"
	"sync"
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
	ErrorCooldown   = 5 * time.Minute
)

// ProviderErrorFunc is called when a provider fails during search.
type ProviderErrorFunc func(providerName string, err error)

type BookService struct {
	providers       []domain.BookProvider
	providerMap     map[string]domain.BookProvider
	settings        domain.UserSettingsRepository
	searchCache     domain.SearchCacheRepository
	onProviderError ProviderErrorFunc
	errorCooldown   sync.Map // provider name -> time.Time
	logger          *log.Logger
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

// SetOnProviderError sets a callback invoked when a provider fails during search.
func (s *BookService) SetOnProviderError(fn ProviderErrorFunc) {
	s.onProviderError = fn
}

func (s *BookService) notifyProviderError(providerName string, err error) {
	if s.onProviderError == nil {
		return
	}

	now := time.Now()
	if v, ok := s.errorCooldown.Load(providerName); ok {
		if now.Before(v.(time.Time).Add(ErrorCooldown)) {
			return
		}
	}
	s.errorCooldown.Store(providerName, now)
	s.onProviderError(providerName, err)
}

// Search queries all providers in parallel, caches results, returns first page.
func (s *BookService) Search(ctx context.Context, telegramID int64, query string) ([]domain.SearchResult, error) {
	s.logger.Info("search started", "telegram_id", telegramID, "query", query)

	if len(query) > MaxQueryLength {
		s.logger.Debug("query truncated", "original_len", len(query), "max", MaxQueryLength)
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
				s.notifyProviderError(p.Name(), err)
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
		s.logger.Debug("provider results collected", "provider", pr.name, "count", len(pr.results))
		allResults = append(allResults, pr.results...)
	}

	if len(allResults) == 0 {
		s.logger.Info("search returned no results", "telegram_id", telegramID, "query", query)
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

	s.logger.Info("search completed", "telegram_id", telegramID, "total", len(allResults))
	return allResults[:end], nil
}

// GetPage returns a slice of results for pagination, plus the total result count.
func (s *BookService) GetPage(ctx context.Context, telegramID int64, offset int) (results []domain.SearchResult, hasMore bool, total int, err error) {
	session, err := s.searchCache.Get(ctx, telegramID)
	if err != nil {
		return nil, false, 0, err
	}
	if session == nil {
		return nil, false, 0, domain.NewError(domain.ErrCodeNotFound, "no search session found")
	}

	total = len(session.Results)
	if offset >= total {
		return nil, false, total, nil
	}

	end := offset + PageSize
	if end > total {
		end = total
	}

	return session.Results[offset:end], end < total, total, nil
}

// Download fetches a book to a temp file using the user's preferred format.
// Returns path to the temp file, filename, file size in bytes, and error.
// The caller is responsible for removing the temp file.
func (s *BookService) Download(ctx context.Context, telegramID int64, resultID string) (string, string, int64, error) {
	userSettings, err := s.GetSettings(ctx, telegramID)
	if err != nil {
		return "", "", 0, err
	}
	return s.DownloadWithFormat(ctx, telegramID, resultID, userSettings.PreferredFormat)
}

// DownloadWithFormat fetches a book in the specified format to a temp file.
// Falls back to the best available format if the requested one is unavailable.
// Returns path to the temp file, filename, file size in bytes, and error.
// The caller is responsible for removing the temp file.
func (s *BookService) DownloadWithFormat(ctx context.Context, telegramID int64, resultID string, format domain.Format) (string, string, int64, error) {
	s.logger.Info("download started", "telegram_id", telegramID, "result_id", resultID, "format", format)

	result, err := s.searchCache.FindResult(ctx, telegramID, resultID)
	if err != nil {
		return "", "", 0, err
	}
	if result == nil {
		s.logger.Debug("result not found in cache", "telegram_id", telegramID, "result_id", resultID)
		return "", "", 0, domain.NewError(domain.ErrCodeNotFound, "result not found in cache")
	}

	if !result.Book.HasFormat(format) {
		if len(result.Book.Formats) == 0 {
			return "", "", 0, domain.NewError(domain.ErrCodeFormatNA, "no formats available")
		}
		format = pickBestFormat(result.Book.Formats)
		s.logger.Debug("requested format unavailable, using fallback", "requested", format, "fallback", format)
	}

	provider, ok := s.providerMap[result.Book.Provider]
	if !ok {
		return "", "", 0, domain.NewError(domain.ErrCodeProviderError, "unknown provider: "+result.Book.Provider)
	}

	dlCtx, cancel := context.WithTimeout(ctx, DownloadTimeout)
	defer cancel()

	reader, filename, err := provider.Download(dlCtx, *result, format)
	if err != nil {
		return "", "", 0, domain.WrapError(domain.ErrCodeProviderError, "download failed", err)
	}
	defer func() { _ = reader.Close() }()

	tmpFile, err := os.CreateTemp("", "book-recon-*."+string(format))
	if err != nil {
		return "", "", 0, domain.WrapError(domain.ErrCodeProviderError, "failed to create temp file", err)
	}

	limited := io.LimitReader(reader, MaxFileSize+1)
	written, err := io.Copy(tmpFile, limited)
	_ = tmpFile.Close()

	if err != nil {
		_ = os.Remove(tmpFile.Name())
		return "", "", 0, domain.WrapError(domain.ErrCodeProviderError, "failed to write temp file", err)
	}

	if written > MaxFileSize {
		_ = os.Remove(tmpFile.Name())
		s.logger.Warn("file too large", "telegram_id", telegramID, "size", written)
		return "", "", 0, domain.NewError(domain.ErrCodeFileTooLarge, "file exceeds 50 MB limit")
	}

	s.logger.Info("download completed", "telegram_id", telegramID, "filename", filename, "size", written)
	return tmpFile.Name(), filename, written, nil
}

// GetResult returns a cached search result by ID, or nil if not found.
func (s *BookService) GetResult(ctx context.Context, telegramID int64, resultID string) (*domain.SearchResult, error) {
	return s.searchCache.FindResult(ctx, telegramID, resultID)
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

// formatPriority defines the fallback order when the user's preferred format is unavailable.
var formatPriority = []domain.Format{domain.FormatEPUB, domain.FormatFB2, domain.FormatMOBI, domain.FormatPDF, domain.FormatDJVU}

// pickBestFormat returns the highest-priority format available in the list.
// Falls back to the first element if none of the prioritised formats match.
func pickBestFormat(available []domain.Format) domain.Format {
	for _, pf := range formatPriority {
		for _, af := range available {
			if pf == af {
				return pf
			}
		}
	}
	return available[0]
}

// GetProvider returns a provider by name.
func (s *BookService) GetProvider(name string) (domain.BookProvider, bool) {
	p, ok := s.providerMap[name]
	return p, ok
}

// CheckHealth runs health checks on all providers that implement domain.HealthChecker.
func (s *BookService) CheckHealth(ctx context.Context) []domain.HealthStatus {
	var statuses []domain.HealthStatus
	for _, p := range s.providers {
		if hc, ok := p.(domain.HealthChecker); ok {
			statuses = append(statuses, hc.CheckHealth(ctx)...)
		}
	}
	return statuses
}

// SetFormat sets the user's preferred format.
func (s *BookService) SetFormat(ctx context.Context, telegramID int64, format domain.Format) error {
	s.logger.Info("format changed", "telegram_id", telegramID, "format", format)
	settings, err := s.GetSettings(ctx, telegramID)
	if err != nil {
		return err
	}
	settings.PreferredFormat = format
	return s.settings.Save(ctx, settings)
}
