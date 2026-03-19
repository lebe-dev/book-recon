package storage

import (
	"database/sql"
	"fmt"

	_ "modernc.org/sqlite"
)

func NewDB(path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, fmt.Errorf("open sqlite: %w", err)
	}

	db.SetMaxOpenConns(1)

	if _, err := db.Exec("PRAGMA journal_mode=WAL"); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("set WAL mode: %w", err)
	}

	if err := migrate(db); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("migrate: %w", err)
	}

	return db, nil
}

func migrate(db *sql.DB) error {
	queries := []string{
		`CREATE TABLE IF NOT EXISTS user_settings (
			telegram_id INTEGER PRIMARY KEY,
			preferred_format TEXT NOT NULL DEFAULT 'epub',
			created_at TEXT NOT NULL DEFAULT (datetime('now')),
			updated_at TEXT NOT NULL DEFAULT (datetime('now'))
		)`,
		`CREATE TABLE IF NOT EXISTS search_sessions (
			telegram_id INTEGER PRIMARY KEY,
			results_json TEXT NOT NULL,
			created_at TEXT NOT NULL DEFAULT (datetime('now'))
		)`,
	}

	for _, q := range queries {
		if _, err := db.Exec(q); err != nil {
			return fmt.Errorf("exec %q: %w", q[:40], err)
		}
	}

	return nil
}
