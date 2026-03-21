package rutracker

import "testing"

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
