package config

import (
	"strings"

	"github.com/caarlos0/env/v11"
	"github.com/joho/godotenv"
)

type Config struct {
	TelegramToken     string   `env:"TELEGRAM_TOKEN,required"`
	AllowedUsers      []string `env:"ALLOWED_USERS" envSeparator:","`
	AdminUsers        []string `env:"ADMIN_USERS" envSeparator:","`
	DBPath            string   `env:"DB_PATH" envDefault:"book-recon.db"`
	LogLevel          string   `env:"LOG_LEVEL" envDefault:"info"`
	UserAgent         string   `env:"USER_AGENT" envDefault:"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"`
	RoyallibBaseURL   string   `env:"ROYALLIB_BASE_URL" envDefault:"https://royallib.com"`
	FlibustaBaseURL   string   `env:"FLIBUSTA_BASE_URL" envDefault:"https://flibusta.is"`
	FlibustaEngine    string   `env:"FLIBUSTA_ENGINE" envDefault:"default"`
	BookSizeThreshold int64    `env:"BOOK_SIZE_THRESHOLD" envDefault:"4096"`
}

func Load() (*Config, error) {
	_ = godotenv.Load() // ignore error if .env does not exist

	var cfg Config
	if err := env.Parse(&cfg); err != nil {
		return nil, err
	}

	cfg.AllowedUsers = normalizeUsernames(cfg.AllowedUsers)
	cfg.AdminUsers = normalizeUsernames(cfg.AdminUsers)

	return &cfg, nil
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
