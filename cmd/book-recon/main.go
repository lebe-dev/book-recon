package main

import (
	"os"
	"os/signal"
	"syscall"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/adapter/config"
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

	if len(cfg.AllowedUsers) == 0 {
		logger.Warn("no allowed users configured, bot will deny all requests")
	}

	db, err := storage.NewDB(cfg.DBPath)
	if err != nil {
		logger.Fatal("failed to open database", "error", err)
	}
	defer func() { _ = db.Close() }()

	userRepo := storage.NewUserSettingsRepo(db)
	searchCache := storage.NewSearchCacheRepo(db)

	royallibProvider := royallib.New(logger)

	providers := []domain.BookProvider{royallibProvider}

	bookService := usecase.NewBookService(providers, userRepo, searchCache, logger)

	bot, err := telegram.New(cfg.TelegramToken, bookService, cfg.AllowedUsers, cfg.AdminUsers, logger)
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
