package config

import (
	"strings"

	"github.com/caarlos0/env/v11"
	"github.com/joho/godotenv"
)

type Config struct {
	TelegramToken string   `env:"TELEGRAM_TOKEN,required"`
	AllowedUsers  []string `env:"ALLOWED_USERS" envSeparator:","`
	AdminUsers    []string `env:"ADMIN_USERS" envSeparator:","`
	DBPath        string   `env:"DB_PATH" envDefault:"book-recon.db"`
	LogLevel      string   `env:"LOG_LEVEL" envDefault:"info"`
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
