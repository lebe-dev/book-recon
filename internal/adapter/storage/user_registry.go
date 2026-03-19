package storage

import (
	"context"
	"database/sql"
)

type UserRegistryRepo struct {
	db *sql.DB
}

func NewUserRegistryRepo(db *sql.DB) *UserRegistryRepo {
	return &UserRegistryRepo{db: db}
}

func (r *UserRegistryRepo) Register(ctx context.Context, telegramID int64, username string) error {
	_, err := r.db.ExecContext(ctx,
		`INSERT INTO users (telegram_id, username) VALUES (?, ?)
		 ON CONFLICT(telegram_id) DO NOTHING`,
		telegramID, username,
	)
	return err
}
