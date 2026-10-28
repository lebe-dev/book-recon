package storage

import (
	"context"
	"database/sql"
	"strconv"
)

type AppSettingsRepo struct {
	db *sql.DB
}

func NewAppSettingsRepo(db *sql.DB) *AppSettingsRepo {
	return &AppSettingsRepo{db: db}
}

func (r *AppSettingsRepo) GetBool(ctx context.Context, key string) (bool, bool, error) {
	var raw string
	err := r.db.QueryRowContext(ctx, `SELECT value FROM app_settings WHERE key = ?`, key).Scan(&raw)
	if err == sql.ErrNoRows {
		return false, false, nil
	}
	if err != nil {
		return false, false, err
	}
	v, err := strconv.ParseBool(raw)
	if err != nil {
		return false, false, err
	}
	return v, true, nil
}

func (r *AppSettingsRepo) SetBool(ctx context.Context, key string, value bool) error {
	_, err := r.db.ExecContext(ctx,
		`INSERT INTO app_settings (key, value, updated_at)
		 VALUES (?, ?, datetime('now'))
		 ON CONFLICT(key) DO UPDATE SET
		   value = excluded.value,
		   updated_at = datetime('now')`,
		key, strconv.FormatBool(value),
	)
	return err
}
