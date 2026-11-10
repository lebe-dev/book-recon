package telegram

import "testing"

func TestLinkCodeFromStart(t *testing.T) {
	tests := []struct {
		text string
		code string
		ok   bool
	}{
		{"/start link_0123abcd", "link_0123abcd", true},
		{"/start  link_x ", "link_x", true},
		{"/start", "", false},
		{"/start other", "", false},
		{"/help link_x", "", false},
		{"link_x", "", false},
		{"/start link_x extra", "", false},
	}
	for _, tt := range tests {
		code, ok := linkCodeFromStart(tt.text)
		if code != tt.code || ok != tt.ok {
			t.Errorf("linkCodeFromStart(%q) = %q, %v; want %q, %v", tt.text, code, ok, tt.code, tt.ok)
		}
	}
}
