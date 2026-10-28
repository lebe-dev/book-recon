package config

import "testing"

func TestParseByteSize(t *testing.T) {
	tests := []struct {
		in      string
		want    ByteSize
		wantErr bool
	}{
		{"52428800", 52428800, false},
		{"0", 0, false},
		{"512B", 512, false},
		{"10KB", 10 << 10, false},
		{"10k", 10 << 10, false},
		{"500MB", 500 << 20, false},
		{"500 MiB", 500 << 20, false},
		{"20GB", 20 << 30, false},
		{"1.5gb", 3 << 29, false},
		{"2TB", 2 << 40, false},
		{"", 0, true},
		{"GB", 0, true},
		{"10XB", 0, true},
		{"-5MB", 0, true},
	}
	for _, tt := range tests {
		got, err := ParseByteSize(tt.in)
		if (err != nil) != tt.wantErr {
			t.Errorf("ParseByteSize(%q) error = %v, wantErr %v", tt.in, err, tt.wantErr)
			continue
		}
		if got != tt.want {
			t.Errorf("ParseByteSize(%q) = %d, want %d", tt.in, got, tt.want)
		}
	}
}
