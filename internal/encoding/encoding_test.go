package encoding_test

import (
	"testing"

	"github.com/lebe-dev/book-recon/internal/encoding"
	"golang.org/x/text/encoding/charmap"
)

func TestToUTF8_AlreadyUTF8(t *testing.T) {
	s := "Купцов"
	if got := encoding.ToUTF8(s, charmap.CodePage866); got != s {
		t.Errorf("got %q, want %q", got, s)
	}
}

func TestToUTF8_CP866(t *testing.T) {
	// "Книга" in CP866: К=0x8A н=0xAD и=0xA8 г=0xA3 а=0xA0
	cp866 := "\x8a\xad\xa8\xa3\xa0"
	got := encoding.ToUTF8(cp866, charmap.CodePage866, charmap.Windows1251)
	if got != "Книга" {
		t.Errorf("CP866: got %q, want %q", got, "Книга")
	}
}

func TestToUTF8_CP1251(t *testing.T) {
	// "Книга" in CP1251: К=0xCA н=0xED и=0xE8 г=0xE3 а=0xE0
	cp1251 := "\xca\xed\xe8\xe3\xe0"
	got := encoding.ToUTF8(cp1251, charmap.Windows1251, charmap.CodePage866)
	if got != "Книга" {
		t.Errorf("CP1251: got %q, want %q", got, "Книга")
	}
}

func TestToUTF8_NoCandidates(t *testing.T) {
	raw := "\x8a\xad\xa8"
	got := encoding.ToUTF8(raw)
	if got != raw {
		t.Errorf("expected original string back, got %q", got)
	}
}

func TestFilenameFromDisposition(t *testing.T) {
	tests := []struct {
		name  string
		input string
		want  string
	}{
		{
			name:  "utf8 percent-encoded",
			input: `attachment; filename="%D0%9A%D1%83%D0%BF%D1%86%D0%BE%D0%B2.epub"`,
			want:  "Купцов.epub",
		},
		{
			name:  "plain ascii",
			input: `attachment; filename="plain.fb2.zip"`,
			want:  "plain.fb2.zip",
		},
		{
			name:  "rfc5987 filename*=",
			input: `attachment; filename*=UTF-8''%D0%9A%D1%83%D0%BF%D1%86%D0%BE%D0%B2.epub`,
			want:  "Купцов.epub",
		},
		{
			name: "cp1251 percent-encoded",
			// "Купцов" in CP1251: К=0xCA у=0xF3 п=0xEF ц=0xF6 о=0xEE в=0xE2
			input: `attachment; filename="%CA%F3%EF%F6%EE%E2.epub"`,
			want:  "Купцов.epub",
		},
		{
			name:  "empty",
			input: "",
			want:  "",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := encoding.FilenameFromDisposition(tt.input)
			if got != tt.want {
				t.Errorf("got %q, want %q", got, tt.want)
			}
		})
	}
}

func TestDecodeZipFilename(t *testing.T) {
	tests := []struct {
		name  string
		input string
		want  string
	}{
		{
			name:  "already utf8",
			input: "Книга.fb2",
			want:  "Книга.fb2",
		},
		{
			name: "cp866",
			// "Книга" in CP866
			input: "\x8a\xad\xa8\xa3\xa0.fb2",
			want:  "Книга.fb2",
		},
		{
			name: "cp1251",
			// "Книга" in CP1251
			input: "\xca\xed\xe8\xe3\xe0.fb2",
			want:  "Книга.fb2",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := encoding.DecodeZipFilename(tt.input)
			if got != tt.want {
				t.Errorf("got %q, want %q", got, tt.want)
			}
		})
	}
}
