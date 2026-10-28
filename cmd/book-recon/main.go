package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/adapter/audiobook"
	"github.com/lebe-dev/book-recon/internal/adapter/config"
	"github.com/lebe-dev/book-recon/internal/adapter/i18n"
	"github.com/lebe-dev/book-recon/internal/adapter/provider/flibusta"
	"github.com/lebe-dev/book-recon/internal/adapter/provider/flibustav2"
	"github.com/lebe-dev/book-recon/internal/adapter/provider/royallib"
	rtprovider "github.com/lebe-dev/book-recon/internal/adapter/provider/rutracker"
	sentryadapter "github.com/lebe-dev/book-recon/internal/adapter/sentry"
	"github.com/lebe-dev/book-recon/internal/adapter/storage"
	"github.com/lebe-dev/book-recon/internal/adapter/telegram"
	"github.com/lebe-dev/book-recon/internal/domain"
	"github.com/lebe-dev/book-recon/internal/usecase"
)

var Version = "dev"

func main() {
	logger := log.NewWithOptions(os.Stderr, log.Options{
		ReportTimestamp: true,
	})

	cfg, err := config.Load()
	if err != nil {
		logger.Fatal("failed to load config", "error", err)
	}

	level, err := log.ParseLevel(cfg.LogLevel)
	if err != nil {
		logger.Warn("invalid log level, using info", "level", cfg.LogLevel)
		level = log.InfoLevel
	}
	logger.SetLevel(level)

	flushSentry, sentryEnabled, err := sentryadapter.Init(cfg.SentryDSN, cfg.SentryEnvironment, Version, logger)
	if err != nil {
		logger.Warn("failed to initialize sentry, continuing without it", "error", err)
	}
	defer flushSentry()
	defer sentryadapter.Recover()
	if sentryEnabled {
		logger.SetOutput(sentryadapter.WrapLogOutput(os.Stderr))
	}

	msg, err := i18n.Load(cfg.Locale)
	if err != nil {
		logger.Fatal("unsupported locale", "locale", cfg.Locale)
	}

	logger.Debug("config loaded", "db_path", cfg.DBPath, "log_level", cfg.LogLevel, "locale", cfg.Locale, "allowed_users", len(cfg.AllowedUsers), "admin_users", len(cfg.AdminUsers))

	db, err := storage.NewDB(cfg.DBPath)
	if err != nil {
		logger.Fatal("failed to open database", "error", err)
	}
	defer func() { _ = db.Close() }()
	logger.Debug("database opened", "path", cfg.DBPath)

	userRepo := storage.NewUserSettingsRepo(db)
	userRegistry := storage.NewUserRegistryRepo(db)
	searchCache := storage.NewSearchCacheRepo(db)
	accessRepo := storage.NewAccessRepo(db)

	var providers []domain.BookProvider

	if cfg.RoyallibEnabled {
		royallibProvider := royallib.New(cfg.RoyallibBaseURL, cfg.UserAgent, cfg.BookSizeThreshold, logger)
		providers = append(providers, royallibProvider)
		logger.Info("provider enabled", "provider", "royallib")
	}

	if cfg.FlibustaEnabled {
		var flibustaProvider domain.BookProvider
		if cfg.FlibustaEngine == "v2" {
			logger.Info("using flibusta engine v2 (OPDS)")
			flibustaProvider = flibustav2.NewDomainProvider(cfg.FlibustaBaseURL, logger)
		} else {
			flibustaProvider = flibusta.New(cfg.FlibustaBaseURL, cfg.UserAgent, logger)
		}
		providers = append(providers, flibustaProvider)
		logger.Info("provider enabled", "provider", "flibusta")
	}

	var rutrackerProvider *rtprovider.Provider
	if cfg.RutrackerEnabled {
		torrentMgr, err := rtprovider.NewTorrentManager(rtprovider.TorrentConfig{
			DownloadDir:     cfg.RutrackerDownloadDir,
			DownloadTimeout: cfg.RutrackerDownloadTimeout,
			MaxTorrentSize:  cfg.RutrackerMaxTorrentSize,
			MaxConcurrent:   3,
		}, logger)
		if err != nil {
			logger.Fatal("failed to create torrent manager", "error", err)
		}
		if err := torrentMgr.CleanupStale(); err != nil {
			logger.Warn("failed to cleanup stale torrents", "error", err)
		}

		rutrackerProvider = rtprovider.New(rtprovider.Config{
			JackettURL:        cfg.JackettURL,
			JackettAPIKey:     cfg.JackettAPIKey,
			JackettIndexer:    cfg.JackettIndexer,
			JackettCategories: cfg.JackettCategories,
			JackettTimeout:    cfg.JackettTimeout,
			DownloadTimeout:   cfg.RutrackerDownloadTimeout,
			MaxBooks:          cfg.RutrackerMaxBooks,
			MaxTorrentSize:    cfg.RutrackerMaxTorrentSize,
			DownloadDir:       cfg.RutrackerDownloadDir,
		}, torrentMgr, logger)
		providers = append(providers, rutrackerProvider)
		logger.Info("provider enabled", "provider", "rutracker")
	}

	if len(providers) == 0 && !cfg.AudiobooksEnabled {
		logger.Fatal("no providers enabled, set ROYALLIB_ENABLED=true, FLIBUSTA_ENABLED=true, RUTRACKER_ENABLED=true and/or AUDIOBOOKS_ENABLED=true")
	}

	var (
		audioService    *usecase.AudiobookService
		audioDownloader *audiobook.Downloader
	)
	if cfg.AudiobooksEnabled {
		audioDownloader, err = audiobook.NewDownloader(audiobook.DownloaderConfig{
			Dir:          cfg.AudiobooksDownloadDir,
			StallTimeout: cfg.AudiobooksStallTimeout,
			AllowUpload:  cfg.AudiobooksAllowUpload,
		}, logger)
		if err != nil {
			logger.Fatal("failed to create audiobook downloader", "error", err)
		}

		jackett := audiobook.NewJackett(audiobook.JackettConfig{
			URL:        cfg.JackettURL,
			APIKey:     cfg.JackettAPIKey,
			Indexer:    cfg.JackettIndexer,
			Categories: cfg.AudiobooksCategories,
			Timeout:    cfg.AudiobooksSearchTimeout,
		}, logger)

		audioService = usecase.NewAudiobookService(jackett, audioDownloader,
			storage.NewAudiobookDownloadRepo(db), storage.NewAppSettingsRepo(db),
			usecase.AudiobookConfig{
				SearchLimit:      cfg.AudiobooksSearchLimit,
				MaxDownloadSize:  int64(cfg.AudiobooksMaxDownloadSize),
				MaxFileSize:      int64(cfg.AudiobooksMaxFileSize),
				StorageMaxSize:   int64(cfg.AudiobooksStorageMaxSize),
				LinkTTL:          cfg.AudiobooksLinkTTL,
				DownloadTimeout:  cfg.AudiobooksDownloadTimeout,
				MaxConcurrent:    cfg.AudiobooksMaxConcurrent,
				MaxPerUser:       cfg.AudiobooksMaxPerUser,
				ProgressInterval: cfg.AudiobooksProgressInterval,
				FileExtensions:   cfg.AudiobooksFileExtensions,
				PublicURL:        cfg.AudiobooksPublicURL,
			}, logger)
		logger.Info("audiobooks enabled", "categories", cfg.AudiobooksCategories, "dir", cfg.AudiobooksDownloadDir,
			"public_url", cfg.AudiobooksPublicURL)
	}

	bookService := usecase.NewBookService(providers, userRepo, searchCache, logger)
	bookService.SetProviderTimeout(cfg.ProviderTimeout)
	accessService := usecase.NewAccessService(accessRepo, userRegistry, logger)

	bot, err := telegram.New(cfg.TelegramToken, bookService, accessService, userRegistry, cfg.AllowedUsers, cfg.AdminUsers, Version, msg, logger)
	if err != nil {
		logger.Fatal("failed to create telegram bot", "error", err)
	}

	bookService.SetOnProviderError(bot.NotifyProviderError)

	var audioWeb *audiobook.WebServer
	if audioService != nil {
		bot.SetAudiobookService(audioService)
		if err := audioService.Start(context.Background()); err != nil {
			logger.Fatal("failed to start audiobook service", "error", err)
		}

		audioWeb = audiobook.NewWebServer(cfg.AudiobooksHTTPListen, cfg.AudiobooksDownloadDir, audioService, audiobook.WebLabels{
			Lang:        cfg.Locale,
			Files:       msg.Audio.WebFiles,
			DownloadAll: msg.Audio.WebDownloadAll,
			ExpiresAt:   msg.Audio.WebExpires,
			Download:    msg.Audio.WebDownload,
			FormatSize:  msg.FormatTorrentSize,
		}, logger)
		go func() {
			defer sentryadapter.Recover()
			if err := audioWeb.Start(); err != nil {
				logger.Fatal("audiobook web server failed", "error", err)
			}
		}()
	}

	go func() {
		defer sentryadapter.Recover()
		bot.Start()
	}()
	logger.Info("bot started", "version", Version)

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("shutting down...")
	bot.Stop()
	if audioWeb != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		if err := audioWeb.Shutdown(ctx); err != nil {
			logger.Warn("audiobook web server shutdown", "error", err)
		}
		cancel()
	}
	if audioService != nil {
		audioService.Close()
		audioDownloader.Close()
	}
	if rutrackerProvider != nil {
		rutrackerProvider.Close()
	}
	logger.Info("bye")
}
