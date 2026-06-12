package sentry

import (
	"bytes"
	"testing"
	"time"

	sentrygo "github.com/getsentry/sentry-go"
)

func TestParseLine(t *testing.T) {
	tests := []struct {
		name      string
		line      string
		wantLevel sentrygo.Level
		wantTitle string
		wantOK    bool
	}{
		{
			name:      "error with timestamp and keyvals",
			line:      "2026/06/12 16:25:24 ERRO failed to download book error=boom id=42",
			wantLevel: sentrygo.LevelError,
			wantTitle: "failed to download book",
			wantOK:    true,
		},
		{
			name:      "fatal level",
			line:      "2026/06/12 16:25:24 FATA failed to open database error=locked",
			wantLevel: sentrygo.LevelFatal,
			wantTitle: "failed to open database",
			wantOK:    true,
		},
		{
			name:   "warn is ignored",
			line:   "2026/06/12 16:25:24 WARN heads up x=1",
			wantOK: false,
		},
		{
			name:   "info is ignored",
			line:   "2026/06/12 16:25:24 INFO bot started version=0.5.0",
			wantOK: false,
		},
		{
			name:      "ansi-wrapped error line",
			line:      "2026/06/12 16:25:24 \x1b[31mERRO\x1b[0m search failed query=tolkien",
			wantLevel: sentrygo.LevelError,
			wantTitle: "search failed",
			wantOK:    true,
		},
		{
			name:      "error without timestamp",
			line:      "ERRO bare message",
			wantLevel: sentrygo.LevelError,
			wantTitle: "bare message",
			wantOK:    true,
		},
		{
			name:   "ERROR word inside an info message is not treated as level",
			line:   "2026/06/12 16:25:24 INFO retry after transient ERROR",
			wantOK: false,
		},
		{
			name:      "error with quoted value containing spaces",
			line:      `2026/06/12 16:25:24 ERRO download bad status error="connection refused now"`,
			wantLevel: sentrygo.LevelError,
			wantTitle: "download bad status",
			wantOK:    true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			level, title, ok := parseLine(tt.line)
			if ok != tt.wantOK {
				t.Fatalf("ok = %v, want %v", ok, tt.wantOK)
			}
			if !ok {
				return
			}
			if level != tt.wantLevel {
				t.Errorf("level = %q, want %q", level, tt.wantLevel)
			}
			if title != tt.wantTitle {
				t.Errorf("title = %q, want %q", title, tt.wantTitle)
			}
		})
	}
}

func TestLogWriterTeesAndCaptures(t *testing.T) {
	var under bytes.Buffer
	var captured []sentrygo.Level
	var flushed int

	w := &logWriter{
		under: &under,
		capture: func(level sentrygo.Level, _, _ string) {
			captured = append(captured, level)
		},
		flush: func(time.Duration) bool { flushed++; return true },
	}

	input := "2026/06/12 16:25:24 INFO ok\n" +
		"2026/06/12 16:25:24 ERRO boom error=x\n" +
		"2026/06/12 16:25:24 FATA dead error=y\n"

	n, err := w.Write([]byte(input))
	if err != nil {
		t.Fatalf("Write returned error: %v", err)
	}
	if n != len(input) {
		t.Errorf("n = %d, want %d", n, len(input))
	}
	if under.String() != input {
		t.Errorf("tee mismatch: got %q", under.String())
	}
	if len(captured) != 2 {
		t.Fatalf("captured %d events, want 2 (%v)", len(captured), captured)
	}
	if captured[0] != sentrygo.LevelError || captured[1] != sentrygo.LevelFatal {
		t.Errorf("captured levels = %v", captured)
	}
	if flushed != 1 {
		t.Errorf("flush called %d times, want 1 (fatal only)", flushed)
	}
}
