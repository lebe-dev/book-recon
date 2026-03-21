package telegram

import "testing"

func TestExtractBroadcastText(t *testing.T) {
	tests := []struct {
		name  string
		input string
		want  string
	}{
		{
			name:  "inline text",
			input: "/whats_new Добавлена поддержка MOBI",
			want:  "Добавлена поддержка MOBI",
		},
		{
			name:  "multiline text",
			input: "/whats_new\nПервая строка\nВторая строка",
			want:  "Первая строка\nВторая строка",
		},
		{
			name:  "no text",
			input: "/whats_new",
			want:  "",
		},
		{
			name:  "only spaces",
			input: "/whats_new   ",
			want:  "",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := extractBroadcastText(tt.input)
			if got != tt.want {
				t.Errorf("extractBroadcastText(%q) = %q, want %q", tt.input, got, tt.want)
			}
		})
	}
}
