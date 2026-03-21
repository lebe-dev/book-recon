package rutracker

import (
	"strings"
	"testing"
)

func TestParseTorznab(t *testing.T) {
	xml := `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed">
  <channel>
    <item>
      <title>Толстой Л.Н. - Война и мир (fb2)</title>
      <size>15728640</size>
      <link>http://jackett:9117/dl/rutracker/?jackett_apikey=abc&amp;file=123</link>
      <category>7000</category>
      <pubDate>Wed, 15 Jan 2025 12:00:00 +0000</pubDate>
      <torznab:attr name="seeders" value="42"/>
      <torznab:attr name="peers" value="5"/>
    </item>
    <item>
      <title>Достоевский - Преступление и наказание (epub)</title>
      <size>5242880</size>
      <link>http://jackett:9117/dl/rutracker/?jackett_apikey=abc&amp;file=456</link>
      <category>7000</category>
      <torznab:attr name="seeders" value="0"/>
      <torznab:attr name="peers" value="1"/>
    </item>
  </channel>
</rss>`

	items, err := ParseTorznab(strings.NewReader(xml))
	if err != nil {
		t.Fatalf("ParseTorznab() error: %v", err)
	}

	if len(items) != 2 {
		t.Fatalf("expected 2 items, got %d", len(items))
	}

	first := items[0]
	if first.Title != "Толстой Л.Н. - Война и мир (fb2)" {
		t.Errorf("title = %q", first.Title)
	}
	if first.Size != 15728640 {
		t.Errorf("size = %d", first.Size)
	}
	if first.Seeders != 42 {
		t.Errorf("seeders = %d", first.Seeders)
	}
	if first.Peers != 5 {
		t.Errorf("peers = %d", first.Peers)
	}
	if !strings.Contains(first.Link, "jackett") {
		t.Errorf("link = %q", first.Link)
	}

	second := items[1]
	if second.Seeders != 0 {
		t.Errorf("second seeders = %d", second.Seeders)
	}
}

func TestParseTorznab_Empty(t *testing.T) {
	xml := `<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0"><channel></channel></rss>`

	items, err := ParseTorznab(strings.NewReader(xml))
	if err != nil {
		t.Fatalf("ParseTorznab() error: %v", err)
	}
	if len(items) != 0 {
		t.Fatalf("expected 0 items, got %d", len(items))
	}
}

func TestParseTorznab_JackettError(t *testing.T) {
	xmlData := `<?xml version="1.0" encoding="UTF-8"?>
<error code="100" description="Invalid API Key" />`

	_, err := ParseTorznab(strings.NewReader(xmlData))
	if err == nil {
		t.Fatal("expected error, got nil")
	}

	te, ok := err.(*TorznabError)
	if !ok {
		t.Fatalf("expected *TorznabError, got %T: %v", err, err)
	}
	if te.Code != "100" {
		t.Errorf("code = %q, want %q", te.Code, "100")
	}
	if te.Description != "Invalid API Key" {
		t.Errorf("description = %q, want %q", te.Description, "Invalid API Key")
	}
	if !strings.Contains(te.Error(), "Invalid API Key") {
		t.Errorf("Error() = %q, want to contain 'Invalid API Key'", te.Error())
	}
}

func TestDetectFormats(t *testing.T) {
	tests := []struct {
		title    string
		expected []string
	}{
		{"Толстой - Война и мир (fb2)", []string{"fb2"}},
		{"Книга (epub, fb2, pdf)", []string{"epub", "fb2", "pdf"}},
		{"Подборка книг (djvu)", []string{"djvu"}},
		{"Просто текст без формата", nil},
		{"EPUB коллекция", []string{"epub"}},
	}

	for _, tt := range tests {
		t.Run(tt.title, func(t *testing.T) {
			got := DetectFormats(tt.title)
			if len(got) != len(tt.expected) {
				t.Errorf("DetectFormats(%q) = %v, want %v", tt.title, got, tt.expected)
				return
			}
			for i := range got {
				if got[i] != tt.expected[i] {
					t.Errorf("DetectFormats(%q)[%d] = %q, want %q", tt.title, i, got[i], tt.expected[i])
				}
			}
		})
	}
}
