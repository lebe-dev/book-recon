package main

import (
	"os"
	"os/signal"
	"syscall"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/adapter/config"
	"github.com/lebe-dev/book-recon/internal/adapter/provider/flibusta"
	"github.com/lebe-dev/book-recon/internal/adapter/provider/flibustav2"
	"github.com/lebe-dev/book-recon/internal/adapter/provider/royallib"
	rtprovider "github.com/lebe-dev/book-recon/internal/adapter/provider/rutracker"
	"github.com/lebe-dev/book-recon/internal/adapter/storage"
	"github.com/lebe-dev/book-recon/internal/adapter/telegram"
	"github.com/lebe-dev/book-recon/internal/domain"
	"github.com/lebe-dev/book-recon/internal/usecase"
)

const Version = "0.4.0"

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
	logger.Debug("config loaded", "db_path", cfg.DBPath, "log_level", cfg.LogLevel, "allowed_users", len(cfg.AllowedUsers), "admin_users", len(cfg.AdminUsers))

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
			JackettURL:      cfg.JackettURL,
			JackettAPIKey:   cfg.JackettAPIKey,
			JackettIndexer:  cfg.JackettIndexer,
			DownloadTimeout: cfg.RutrackerDownloadTimeout,
			MaxBooks:        cfg.RutrackerMaxBooks,
			MaxTorrentSize:  cfg.RutrackerMaxTorrentSize,
			DownloadDir:     cfg.RutrackerDownloadDir,
		}, torrentMgr, logger)
		providers = append(providers, rutrackerProvider)
		logger.Info("provider enabled", "provider", "rutracker")
	}

	if len(providers) == 0 {
		logger.Fatal("no providers enabled, set ROYALLIB_ENABLED=true, FLIBUSTA_ENABLED=true, and/or RUTRACKER_ENABLED=true")
	}

	bookService := usecase.NewBookService(providers, userRepo, searchCache, logger)
	accessService := usecase.NewAccessService(accessRepo, userRegistry, logger)

	bot, err := telegram.New(cfg.TelegramToken, bookService, accessService, userRegistry, cfg.AllowedUsers, cfg.AdminUsers, Version, logger)
	if err != nil {
		logger.Fatal("failed to create telegram bot", "error", err)
	}

	bookService.SetOnProviderError(bot.NotifyProviderError)

	go bot.Start()
	logger.Info("bot started", "version", Version)

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("shutting down...")
	bot.Stop()
	if rutrackerProvider != nil {
		rutrackerProvider.Close()
	}
	logger.Info("bye")
}
