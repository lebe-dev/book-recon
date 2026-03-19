package storage

import (
	"context"
	"database/sql"

	"github.com/lebe-dev/book-recon/internal/domain"
)

type UserSettingsRepo struct {
	db *sql.DB
}

func NewUserSettingsRepo(db *sql.DB) *UserSettingsRepo {
	return &UserSettingsRepo{db: db}
}

func (r *UserSettingsRepo) Get(ctx context.Context, telegramID int64) (*domain.UserSettings, error) {
	row := r.db.QueryRowContext(ctx,
		`SELECT telegram_id, preferred_format FROM user_settings WHERE telegram_id = ?`,
		telegramID,
	)

	var s domain.UserSettings
	var format string
	if err := row.Scan(&s.TelegramID, &format); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}
	s.PreferredFormat = domain.Format(format)
	return &s, nil
}

func (r *UserSettingsRepo) Save(ctx context.Context, settings *domain.UserSettings) error {
	_, err := r.db.ExecContext(ctx,
		`INSERT INTO user_settings (telegram_id, preferred_format, updated_at)
		 VALUES (?, ?, datetime('now'))
		 ON CONFLICT(telegram_id) DO UPDATE SET
		   preferred_format = excluded.preferred_format,
		   updated_at = datetime('now')`,
		settings.TelegramID, string(settings.PreferredFormat),
	)
	return err
}
