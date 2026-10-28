package config

import (
	"errors"
	"strings"
	"time"

	"github.com/caarlos0/env/v11"
	"github.com/joho/godotenv"
)

type Config struct {
	TelegramToken     string   `env:"TELEGRAM_TOKEN,required"`
	AllowedUsers      []string `env:"ALLOWED_USERS" envSeparator:","`
	AdminUsers        []string `env:"ADMIN_USERS" envSeparator:","`
	DBPath            string   `env:"DB_PATH" envDefault:"book-recon.db"`
	Locale            string   `env:"LOCALE" envDefault:"en"`
	LogLevel          string   `env:"LOG_LEVEL" envDefault:"info"`
	UserAgent         string   `env:"USER_AGENT" envDefault:"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"`
	RoyallibBaseURL   string   `env:"ROYALLIB_BASE_URL" envDefault:"https://royallib.com"`
	FlibustaBaseURL   string   `env:"FLIBUSTA_BASE_URL" envDefault:"https://flibusta.is"`
	FlibustaEngine    string   `env:"FLIBUSTA_ENGINE" envDefault:"default"`
	BookSizeThreshold int64    `env:"BOOK_SIZE_THRESHOLD" envDefault:"4096"`
	RoyallibEnabled   bool     `env:"ROYALLIB_ENABLED" envDefault:"false"`
	FlibustaEnabled   bool     `env:"FLIBUSTA_ENABLED" envDefault:"true"`

	// ProviderTimeout bounds a single provider's search during a parallel query.
	ProviderTimeout time.Duration `env:"PROVIDER_TIMEOUT" envDefault:"30s"`

	// Sentry error reporting. Empty SentryDSN disables Sentry entirely.
	SentryDSN         string `env:"SENTRY_DSN"`
	SentryEnvironment string `env:"SENTRY_ENVIRONMENT"`

	// RuTracker via Jackett
	RutrackerEnabled         bool          `env:"RUTRACKER_ENABLED" envDefault:"false"`
	JackettURL               string        `env:"JACKETT_URL" envDefault:"http://localhost:9117"`
	JackettAPIKey            string        `env:"JACKETT_API_KEY"`
	JackettIndexer           string        `env:"JACKETT_INDEXER" envDefault:"rutracker"`
	JackettCategories        []string      `env:"JACKETT_CATEGORIES" envSeparator:","`
	RutrackerDownloadTimeout time.Duration `env:"RUTRACKER_DOWNLOAD_TIMEOUT" envDefault:"5m"`
	RutrackerMaxBooks        int           `env:"RUTRACKER_MAX_BOOKS" envDefault:"5"`
	RutrackerMaxTorrentSize  int64         `env:"RUTRACKER_MAX_TORRENT_SIZE" envDefault:"52428800"`
	RutrackerDownloadDir     string        `env:"RUTRACKER_DOWNLOAD_DIR" envDefault:"/tmp/book-recon-torrents"`

	// Audiobooks from RuTracker via Jackett. AUDIOBOOKS_ENABLED is the master switch:
	// when false the feature can't be turned on from the bot.
	AudiobooksEnabled          bool          `env:"AUDIOBOOKS_ENABLED" envDefault:"false"`
	AudiobooksCategories       []string      `env:"AUDIOBOOKS_CATEGORIES" envSeparator:"," envDefault:"3030"`
	AudiobooksSearchLimit      int           `env:"AUDIOBOOKS_SEARCH_LIMIT" envDefault:"30"`
	AudiobooksSearchTimeout    time.Duration `env:"AUDIOBOOKS_SEARCH_TIMEOUT" envDefault:"30s"`
	AudiobooksDownloadDir      string        `env:"AUDIOBOOKS_DOWNLOAD_DIR" envDefault:"audiobooks"`
	AudiobooksDownloadTimeout  time.Duration `env:"AUDIOBOOKS_DOWNLOAD_TIMEOUT" envDefault:"3h"`
	AudiobooksStallTimeout     time.Duration `env:"AUDIOBOOKS_STALL_TIMEOUT" envDefault:"10m"`
	AudiobooksMaxDownloadSize  ByteSize      `env:"AUDIOBOOKS_MAX_DOWNLOAD_SIZE" envDefault:"4GB"`
	AudiobooksMaxFileSize      ByteSize      `env:"AUDIOBOOKS_MAX_FILE_SIZE" envDefault:"2GB"`
	AudiobooksStorageMaxSize   ByteSize      `env:"AUDIOBOOKS_STORAGE_MAX_SIZE" envDefault:"20GB"`
	AudiobooksLinkTTL          time.Duration `env:"AUDIOBOOKS_LINK_TTL" envDefault:"24h"`
	AudiobooksMaxConcurrent    int           `env:"AUDIOBOOKS_MAX_CONCURRENT" envDefault:"2"`
	AudiobooksMaxPerUser       int           `env:"AUDIOBOOKS_MAX_PER_USER" envDefault:"1"`
	AudiobooksProgressInterval time.Duration `env:"AUDIOBOOKS_PROGRESS_INTERVAL" envDefault:"30s"`
	AudiobooksFileExtensions   []string      `env:"AUDIOBOOKS_FILE_EXTENSIONS" envSeparator:"," envDefault:"mp3,m4a,m4b,aac,ogg,opus,flac,wav,wma"`
	AudiobooksAllowUpload      bool          `env:"AUDIOBOOKS_ALLOW_UPLOAD" envDefault:"true"`
	AudiobooksHTTPListen       string        `env:"AUDIOBOOKS_HTTP_LISTEN" envDefault:":8080"`
	AudiobooksPublicURL        string        `env:"AUDIOBOOKS_PUBLIC_URL"`
}

func Load() (*Config, error) {
	_ = godotenv.Load() // ignore error if .env does not exist

	var cfg Config
	if err := env.Parse(&cfg); err != nil {
		return nil, err
	}

	cfg.AllowedUsers = normalizeUsernames(cfg.AllowedUsers)
	cfg.AdminUsers = normalizeUsernames(cfg.AdminUsers)
	cfg.AudiobooksPublicURL = strings.TrimRight(strings.TrimSpace(cfg.AudiobooksPublicURL), "/")

	if err := cfg.validate(); err != nil {
		return nil, err
	}

	return &cfg, nil
}

func (c *Config) validate() error {
	if !c.AudiobooksEnabled {
		return nil
	}

	var errs []error
	if c.AudiobooksPublicURL == "" {
		errs = append(errs, errors.New("AUDIOBOOKS_PUBLIC_URL is required when AUDIOBOOKS_ENABLED=true"))
	}
	if c.JackettAPIKey == "" {
		errs = append(errs, errors.New("JACKETT_API_KEY is required when AUDIOBOOKS_ENABLED=true"))
	}
	if c.AudiobooksMaxConcurrent < 1 {
		errs = append(errs, errors.New("AUDIOBOOKS_MAX_CONCURRENT must be >= 1"))
	}
	if c.AudiobooksMaxPerUser < 1 {
		errs = append(errs, errors.New("AUDIOBOOKS_MAX_PER_USER must be >= 1"))
	}
	if c.AudiobooksLinkTTL <= 0 {
		errs = append(errs, errors.New("AUDIOBOOKS_LINK_TTL must be > 0"))
	}
	if c.AudiobooksStorageMaxSize > 0 && c.AudiobooksMaxDownloadSize > c.AudiobooksStorageMaxSize {
		errs = append(errs, errors.New("AUDIOBOOKS_MAX_DOWNLOAD_SIZE must not exceed AUDIOBOOKS_STORAGE_MAX_SIZE"))
	}
	return errors.Join(errs...)
}

// normalizeUsernames strips leading '@' and lowercases each username.
func normalizeUsernames(names []string) []string {
	out := make([]string, 0, len(names))
	for _, n := range names {
		n = strings.TrimSpace(n)
		n = strings.TrimPrefix(n, "@")
		n = strings.ToLower(n)
		if n != "" {
			out = append(out, n)
		}
	}
	return out
}
