package storage

import (
	"context"
	"database/sql"

	"github.com/lebe-dev/book-recon/internal/domain"
)

type AccessRepo struct {
	db *sql.DB
}

func NewAccessRepo(db *sql.DB) *AccessRepo {
	return &AccessRepo{db: db}
}

func (r *AccessRepo) GetStatus(ctx context.Context, telegramID int64) (domain.AccessStatus, error) {
	var status string
	err := r.db.QueryRowContext(ctx,
		`SELECT status FROM access_requests WHERE telegram_id = ?`, telegramID,
	).Scan(&status)
	if err == sql.ErrNoRows {
		return "", nil
	}
	if err != nil {
		return "", err
	}
	return domain.AccessStatus(status), nil
}

func (r *AccessRepo) CreateRequest(ctx context.Context, req domain.AccessRequest) (bool, error) {
	result, err := r.db.ExecContext(ctx,
		`INSERT INTO access_requests (telegram_id, username, first_name, status)
		 VALUES (?, ?, ?, ?)
		 ON CONFLICT(telegram_id) DO NOTHING`,
		req.TelegramID, req.Username, req.FirstName, req.Status,
	)
	if err != nil {
		return false, err
	}

	rows, err := result.RowsAffected()
	if err != nil {
		return false, err
	}
	return rows > 0, nil
}

func (r *AccessRepo) SetStatus(ctx context.Context, telegramID int64, status domain.AccessStatus) error {
	_, err := r.db.ExecContext(ctx,
		`UPDATE access_requests SET status = ?, updated_at = datetime('now') WHERE telegram_id = ?`,
		string(status), telegramID,
	)
	return err
}

func (r *AccessRepo) DeleteRequest(ctx context.Context, telegramID int64) error {
	_, err := r.db.ExecContext(ctx,
		`DELETE FROM access_requests WHERE telegram_id = ?`, telegramID,
	)
	return err
}

func (r *AccessRepo) ListByStatus(ctx context.Context, status domain.AccessStatus) ([]domain.AccessRequest, error) {
	rows, err := r.db.QueryContext(ctx,
		`SELECT telegram_id, username, first_name, status FROM access_requests WHERE status = ? ORDER BY created_at DESC`,
		string(status),
	)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	var result []domain.AccessRequest
	for rows.Next() {
		var req domain.AccessRequest
		var s string
		if err := rows.Scan(&req.TelegramID, &req.Username, &req.FirstName, &s); err != nil {
			return nil, err
		}
		req.Status = domain.AccessStatus(s)
		result = append(result, req)
	}
	return result, rows.Err()
}
