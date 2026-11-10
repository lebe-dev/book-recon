package storage

import (
	"context"
	"database/sql"
	"errors"
	"time"

	"github.com/lebe-dev/book-recon/internal/domain"
)

type AppClientRepo struct {
	db *sql.DB
}

func NewAppClientRepo(db *sql.DB) *AppClientRepo {
	return &AppClientRepo{db: db}
}

const appClientColumns = `id, token_hash, device_name, telegram_id, username, link_code, link_expires_at, created_at, last_seen_at`

func (r *AppClientRepo) Create(ctx context.Context, c domain.AppClient) error {
	_, err := r.db.ExecContext(ctx,
		`INSERT INTO app_clients (`+appClientColumns+`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)`,
		c.ID, c.TokenHash, c.DeviceName, c.TelegramID, c.Username, nullIfEmpty(c.LinkCode),
		formatTime(c.LinkExpiresAt), formatTime(c.CreatedAt), formatTime(c.LastSeenAt),
	)
	return err
}

func (r *AppClientRepo) GetByTokenHash(ctx context.Context, tokenHash string) (*domain.AppClient, error) {
	return r.getOne(ctx, `SELECT `+appClientColumns+` FROM app_clients WHERE token_hash = ?`, tokenHash)
}

func (r *AppClientRepo) GetByLinkCode(ctx context.Context, code string) (*domain.AppClient, error) {
	return r.getOne(ctx, `SELECT `+appClientColumns+` FROM app_clients WHERE link_code = ?`, code)
}

func (r *AppClientRepo) SetLinkCode(ctx context.Context, id, code string, expiresAt time.Time) error {
	_, err := r.db.ExecContext(ctx,
		`UPDATE app_clients SET link_code = ?, link_expires_at = ? WHERE id = ?`,
		nullIfEmpty(code), formatTime(expiresAt), id,
	)
	return err
}

func (r *AppClientRepo) Link(ctx context.Context, id string, telegramID int64, username string) error {
	_, err := r.db.ExecContext(ctx,
		`UPDATE app_clients SET telegram_id = ?, username = ?, link_code = NULL, link_expires_at = '' WHERE id = ?`,
		telegramID, username, id,
	)
	return err
}

func (r *AppClientRepo) Unlink(ctx context.Context, id string) error {
	_, err := r.db.ExecContext(ctx,
		`UPDATE app_clients SET telegram_id = 0, username = '' WHERE id = ?`, id,
	)
	return err
}

func (r *AppClientRepo) Delete(ctx context.Context, id string) error {
	_, err := r.db.ExecContext(ctx, `DELETE FROM app_clients WHERE id = ?`, id)
	return err
}

func (r *AppClientRepo) Touch(ctx context.Context, id string, at time.Time) error {
	_, err := r.db.ExecContext(ctx, `UPDATE app_clients SET last_seen_at = ? WHERE id = ?`, formatTime(at), id)
	return err
}

func (r *AppClientRepo) DeleteUnlinkedBefore(ctx context.Context, t time.Time) (int64, error) {
	result, err := r.db.ExecContext(ctx,
		`DELETE FROM app_clients WHERE telegram_id = 0 AND created_at < ?`, formatTime(t),
	)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}

func (r *AppClientRepo) CountLinked(ctx context.Context) (map[int64]int, error) {
	rows, err := r.db.QueryContext(ctx,
		`SELECT telegram_id, COUNT(*) FROM app_clients WHERE telegram_id != 0 GROUP BY telegram_id`,
	)
	if err != nil {
		return nil, err
	}
	defer func() { _ = rows.Close() }()

	counts := make(map[int64]int)
	for rows.Next() {
		var id int64
		var n int
		if err := rows.Scan(&id, &n); err != nil {
			return nil, err
		}
		counts[id] = n
	}
	return counts, rows.Err()
}

func (r *AppClientRepo) getOne(ctx context.Context, query string, arg any) (*domain.AppClient, error) {
	var (
		c                                    domain.AppClient
		linkCode                             sql.NullString
		linkExpiresAt, createdAt, lastSeenAt string
	)
	err := r.db.QueryRowContext(ctx, query, arg).Scan(
		&c.ID, &c.TokenHash, &c.DeviceName, &c.TelegramID, &c.Username, &linkCode,
		&linkExpiresAt, &createdAt, &lastSeenAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	c.LinkCode = linkCode.String
	c.LinkExpiresAt = parseTime(linkExpiresAt)
	c.CreatedAt = parseTime(createdAt)
	c.LastSeenAt = parseTime(lastSeenAt)
	return &c, nil
}

// formatTime stores times as RFC 3339 in UTC, so text comparison orders them; zero is empty.
func formatTime(t time.Time) string {
	if t.IsZero() {
		return ""
	}
	return t.UTC().Format(time.RFC3339)
}

func parseTime(s string) time.Time {
	if s == "" {
		return time.Time{}
	}
	t, _ := time.Parse(time.RFC3339, s)
	return t
}

// nullIfEmpty keeps the unique index on link_code free of empty strings.
func nullIfEmpty(s string) any {
	if s == "" {
		return nil
	}
	return s
}
