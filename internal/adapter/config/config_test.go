package config

import (
	"strings"
	"testing"
)

func TestLoadAudiobookDefaults(t *testing.T) {
	t.Setenv("TELEGRAM_TOKEN", "x")
	t.Setenv("AUDIOBOOKS_ENABLED", "false")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error: %v", err)
	}
	if cfg.AudiobooksStorageMaxSize != 20<<30 {
		t.Errorf("AudiobooksStorageMaxSize = %d, want 20GB", cfg.AudiobooksStorageMaxSize)
	}
	if len(cfg.AudiobooksCategories) != 1 || cfg.AudiobooksCategories[0] != "3030" {
		t.Errorf("AudiobooksCategories = %v, want [3030]", cfg.AudiobooksCategories)
	}
}

func TestLoadAudiobooksRequiresPublicURLAndAPIKey(t *testing.T) {
	t.Setenv("TELEGRAM_TOKEN", "x")
	t.Setenv("AUDIOBOOKS_ENABLED", "true")
	t.Setenv("AUDIOBOOKS_PUBLIC_URL", "")
	t.Setenv("JACKETT_API_KEY", "")

	_, err := Load()
	if err == nil {
		t.Fatal("Load() expected error")
	}
	for _, v := range []string{"AUDIOBOOKS_PUBLIC_URL", "JACKETT_API_KEY"} {
		if !strings.Contains(err.Error(), v) {
			t.Errorf("error %q does not mention %s", err, v)
		}
	}
}

func TestLoadAudiobooksEnabled(t *testing.T) {
	t.Setenv("TELEGRAM_TOKEN", "x")
	t.Setenv("AUDIOBOOKS_ENABLED", "true")
	t.Setenv("AUDIOBOOKS_PUBLIC_URL", "https://audio.example.com/")
	t.Setenv("JACKETT_API_KEY", "key")
	t.Setenv("AUDIOBOOKS_MAX_DOWNLOAD_SIZE", "1GB")

	cfg, err := Load()
	if err != nil {
		t.Fatalf("Load() error: %v", err)
	}
	if cfg.AudiobooksPublicURL != "https://audio.example.com" {
		t.Errorf("AudiobooksPublicURL = %q, trailing slash not trimmed", cfg.AudiobooksPublicURL)
	}
	if cfg.AudiobooksMaxDownloadSize != 1<<30 {
		t.Errorf("AudiobooksMaxDownloadSize = %d, want 1GB", cfg.AudiobooksMaxDownloadSize)
	}
}
