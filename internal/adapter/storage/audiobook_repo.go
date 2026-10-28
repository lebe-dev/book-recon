package storage

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"time"

	"github.com/lebe-dev/book-recon/internal/domain"
)

type AudiobookDownloadRepo struct {
	db *sql.DB
}

func NewAudiobookDownloadRepo(db *sql.DB) *AudiobookDownloadRepo {
	return &AudiobookDownloadRepo{db: db}
}

func (r *AudiobookDownloadRepo) Save(ctx context.Context, d *domain.AudiobookDownload) error {
	files := d.Files
	if files == nil {
		files = []domain.StoredFile{}
	}
	filesJSON, err := json.Marshal(files)
	if err != nil {
		return fmt.Errorf("marshal files: %w", err)
	}

	var expiresAt string
	if !d.ExpiresAt.IsZero() {
		expiresAt = d.ExpiresAt.UTC().Format(time.RFC3339)
	}

	_, err = r.db.ExecContext(ctx,
		`INSERT INTO audiobook_downloads
		   (id, telegram_id, chat_id, status_message_id, title, status, files_json, size, created_at, expires_at)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT(id) DO UPDATE SET
		   status = excluded.status,
		   files_json = excluded.files_json,
		   size = excluded.size,
		   expires_at = excluded.expires_at`,
		d.ID, d.TelegramID, d.ChatID, d.StatusMessageID, d.Title, string(d.Status),
		string(filesJSON), d.Size, d.CreatedAt.UTC().Format(time.RFC3339), expiresAt,
	)
	return err
}

func (r *AudiobookDownloadRepo) List(ctx context.Context) ([]domain.AudiobookDownload, error) {
	rows, err := r.db.QueryContext(ctx,
		`SELECT id, telegram_id, chat_id, status_message_id, title, status, files_json, size, created_at, expires_at
		 FROM audiobook_downloads ORDER BY created_at`)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	var out []domain.AudiobookDownload
	for rows.Next() {
		var (
			d                    domain.AudiobookDownload
			status, filesJSON    string
			createdAt, expiresAt string
		)
		if err := rows.Scan(&d.ID, &d.TelegramID, &d.ChatID, &d.StatusMessageID, &d.Title, &status,
			&filesJSON, &d.Size, &createdAt, &expiresAt); err != nil {
			return nil, err
		}
		d.Status = domain.AudiobookJobStatus(status)
		if err := json.Unmarshal([]byte(filesJSON), &d.Files); err != nil {
			return nil, fmt.Errorf("unmarshal files of %s: %w", d.ID, err)
		}
		d.CreatedAt, _ = time.Parse(time.RFC3339, createdAt)
		if expiresAt != "" {
			d.ExpiresAt, _ = time.Parse(time.RFC3339, expiresAt)
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

func (r *AudiobookDownloadRepo) Delete(ctx context.Context, id string) error {
	_, err := r.db.ExecContext(ctx, `DELETE FROM audiobook_downloads WHERE id = ?`, id)
	return err
}
