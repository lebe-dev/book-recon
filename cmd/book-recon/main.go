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
	"github.com/lebe-dev/book-recon/internal/adapter/storage"
	"github.com/lebe-dev/book-recon/internal/adapter/telegram"
	"github.com/lebe-dev/book-recon/internal/domain"
	"github.com/lebe-dev/book-recon/internal/usecase"
)

const Version = "0.1.0"

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

	if len(cfg.AllowedUsers) == 0 {
		logger.Warn("no allowed users configured, bot will deny all requests")
	}

	db, err := storage.NewDB(cfg.DBPath)
	if err != nil {
		logger.Fatal("failed to open database", "error", err)
	}
	defer func() { _ = db.Close() }()
	logger.Debug("database opened", "path", cfg.DBPath)

	userRepo := storage.NewUserSettingsRepo(db)
	userRegistry := storage.NewUserRegistryRepo(db)
	searchCache := storage.NewSearchCacheRepo(db)

	royallibProvider := royallib.New(cfg.RoyallibBaseURL, cfg.UserAgent, logger)

	var flibustaProvider domain.BookProvider
	if cfg.FlibustaEngine == "v2" {
		logger.Info("using flibusta engine v2 (OPDS)")
		flibustaProvider = flibustav2.NewDomainProvider(cfg.FlibustaBaseURL, logger)
	} else {
		flibustaProvider = flibusta.New(cfg.FlibustaBaseURL, cfg.UserAgent, logger)
	}

	providers := []domain.BookProvider{royallibProvider, flibustaProvider}

	bookService := usecase.NewBookService(providers, userRepo, searchCache, logger)

	bot, err := telegram.New(cfg.TelegramToken, bookService, userRegistry, cfg.AllowedUsers, cfg.AdminUsers, Version, logger)
	if err != nil {
		logger.Fatal("failed to create telegram bot", "error", err)
	}

	go bot.Start()
	logger.Info("bot started", "version", Version)

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	logger.Info("shutting down...")
	bot.Stop()
	logger.Info("bye")
}
