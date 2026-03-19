package storage

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"time"

	"github.com/lebe-dev/book-recon/internal/domain"
)

type SearchCacheRepo struct {
	db *sql.DB
}

func NewSearchCacheRepo(db *sql.DB) *SearchCacheRepo {
	return &SearchCacheRepo{db: db}
}

func (r *SearchCacheRepo) Save(ctx context.Context, session *domain.SearchSession) error {
	data, err := json.Marshal(session.Results)
	if err != nil {
		return fmt.Errorf("marshal results: %w", err)
	}

	_, err = r.db.ExecContext(ctx,
		`INSERT OR REPLACE INTO search_sessions (telegram_id, results_json, created_at)
		 VALUES (?, ?, datetime('now'))`,
		session.TelegramID, string(data),
	)
	if err != nil {
		return err
	}

	// Cleanup expired sessions
	_, _ = r.db.ExecContext(ctx,
		`DELETE FROM search_sessions WHERE created_at < datetime('now', '-30 minutes')`,
	)

	return nil
}

func (r *SearchCacheRepo) Get(ctx context.Context, telegramID int64) (*domain.SearchSession, error) {
	row := r.db.QueryRowContext(ctx,
		`SELECT results_json, created_at FROM search_sessions WHERE telegram_id = ?`,
		telegramID,
	)

	var resultsJSON string
	var createdAt string
	if err := row.Scan(&resultsJSON, &createdAt); err != nil {
		if err == sql.ErrNoRows {
			return nil, nil
		}
		return nil, err
	}

	var results []domain.SearchResult
	if err := json.Unmarshal([]byte(resultsJSON), &results); err != nil {
		return nil, fmt.Errorf("unmarshal results: %w", err)
	}

	t, err := time.Parse("2006-01-02 15:04:05", createdAt)
	if err != nil {
		return nil, fmt.Errorf("parse created_at: %w", err)
	}

	return &domain.SearchSession{
		TelegramID: telegramID,
		Results:    results,
		CreatedAt:  t,
	}, nil
}

func (r *SearchCacheRepo) FindResult(ctx context.Context, telegramID int64, resultID string) (*domain.SearchResult, error) {
	session, err := r.Get(ctx, telegramID)
	if err != nil {
		return nil, err
	}
	if session == nil {
		return nil, nil
	}

	for _, res := range session.Results {
		if res.ID == resultID {
			return &res, nil
		}
	}
	return nil, nil
}

func (r *SearchCacheRepo) DeleteExpired(ctx context.Context, ttl time.Duration) error {
	minutes := int(ttl.Minutes())
	_, err := r.db.ExecContext(ctx,
		fmt.Sprintf(`DELETE FROM search_sessions WHERE created_at < datetime('now', '-%d minutes')`, minutes),
	)
	return err
}
