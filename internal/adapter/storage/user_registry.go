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
		 ON CONFLICT(telegram_id) DO UPDATE SET username = excluded.username`,
		telegramID, username,
	)
	return err
}

func (r *UserRegistryRepo) GetIDByUsername(ctx context.Context, username string) (int64, error) {
	var id int64
	err := r.db.QueryRowContext(ctx,
		`SELECT telegram_id FROM users WHERE LOWER(username) = LOWER(?)`, username,
	).Scan(&id)
	if err != nil {
		return 0, err
	}
	return id, nil
}

func (r *UserRegistryRepo) ListAllIDs(ctx context.Context) ([]int64, error) {
	rows, err := r.db.QueryContext(ctx, `SELECT telegram_id FROM users`)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		ids = append(ids, id)
	}
	return ids, rows.Err()
}
