package rutracker

import (
	"testing"
	"time"

	"github.com/charmbracelet/log"
)

func TestNewJackettTimeout(t *testing.T) {
	tests := []struct {
		name string
		cfg  time.Duration
		want time.Duration
	}{
		{name: "default when unset", cfg: 0, want: defaultJackettTimeout},
		{name: "explicit value", cfg: 90 * time.Second, want: 90 * time.Second},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			p := New(Config{JackettTimeout: tt.cfg}, nil, log.New(nil))
			if p.httpClient.Timeout != tt.want {
				t.Errorf("timeout = %v, want %v", p.httpClient.Timeout, tt.want)
			}
		})
	}
}

func TestParseRutrackerTitle(t *testing.T) {
	tests := []struct {
		raw    string
		title  string
		author string
	}{
		{
			raw:    "Толстой Л.Н. - Война и мир (fb2)",
			title:  "Война и мир",
			author: "Толстой Л.Н.",
		},
		{
			raw:    "Достоевский Ф.М. — Преступление и наказание (epub, fb2)",
			title:  "Преступление и наказание",
			author: "Достоевский Ф.М.",
		},
		{
			raw:    "Просто название книги",
			title:  "Просто название книги",
			author: "",
		},
		{
			raw:    "Автор - Книга",
			title:  "Книга",
			author: "Автор",
		},
		{
			raw:    "Автор - Книга (pdf, djvu)",
			title:  "Книга",
			author: "Автор",
		},
		{
			raw:    "Книга (без формата в скобках)",
			title:  "Книга (без формата в скобках)",
			author: "",
		},
	}

	for _, tt := range tests {
		t.Run(tt.raw, func(t *testing.T) {
			title, author := parseRutrackerTitle(tt.raw)
			if title != tt.title {
				t.Errorf("title = %q, want %q", title, tt.title)
			}
			if author != tt.author {
				t.Errorf("author = %q, want %q", author, tt.author)
			}
		})
	}
}
