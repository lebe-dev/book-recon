package i18n

import (
	"testing"
)

func TestLoadEN(t *testing.T) {
	msg, err := Load("en")
	if err != nil {
		t.Fatalf("Load(en) returned error: %v", err)
	}
	if msg == nil {
		t.Fatal("Load(en) returned nil")
	}
}

func TestLoadRU(t *testing.T) {
	msg, err := Load("ru")
	if err != nil {
		t.Fatalf("Load(ru) returned error: %v", err)
	}
	if msg == nil {
		t.Fatal("Load(ru) returned nil")
	}
}

func TestLoadUnsupported(t *testing.T) {
	_, err := Load("fr")
	if err == nil {
		t.Fatal("Load(fr) expected error, got nil")
	}
}

func TestFoundBooksEN(t *testing.T) {
	msg, _ := Load("en")
	tests := []struct {
		n    int
		want string
	}{
		{1, "Found 1 book"},
		{2, "Found 2 books"},
		{5, "Found 5 books"},
		{20, "Found 20 books"},
	}
	for _, tt := range tests {
		got := msg.FoundBooks(tt.n)
		if got != tt.want {
			t.Errorf("FoundBooks(%d) = %q, want %q", tt.n, got, tt.want)
		}
	}
}

func TestFoundBooksRU(t *testing.T) {
	msg, _ := Load("ru")
	tests := []struct {
		n    int
		want string
	}{
		{1, "Найдена 1 книга"},
		{2, "Найдено 2 книги"},
		{3, "Найдено 3 книги"},
		{4, "Найдено 4 книги"},
		{5, "Найдено 5 книг"},
		{11, "Найдено 11 книг"},
		{21, "Найдено 21 книг"},
	}
	for _, tt := range tests {
		got := msg.FoundBooks(tt.n)
		if got != tt.want {
			t.Errorf("FoundBooks(%d) = %q, want %q", tt.n, got, tt.want)
		}
	}
}

func TestFormatFileSizeEN(t *testing.T) {
	msg, _ := Load("en")
	tests := []struct {
		bytes int64
		want  string
	}{
		{500, "500 B"},
		{2048, "2 KB"},
		{1572864, "1.5 MB"},
	}
	for _, tt := range tests {
		got := msg.FormatFileSize(tt.bytes)
		if got != tt.want {
			t.Errorf("FormatFileSize(%d) = %q, want %q", tt.bytes, got, tt.want)
		}
	}
}

func TestFormatFileSizeRU(t *testing.T) {
	msg, _ := Load("ru")
	tests := []struct {
		bytes int64
		want  string
	}{
		{500, "500 Б"},
		{2048, "2 КБ"},
		{1572864, "1.5 МБ"},
	}
	for _, tt := range tests {
		got := msg.FormatFileSize(tt.bytes)
		if got != tt.want {
			t.Errorf("FormatFileSize(%d) = %q, want %q", tt.bytes, got, tt.want)
		}
	}
}

func TestFormatTorrentSizeEN(t *testing.T) {
	msg, _ := Load("en")
	got := msg.FormatTorrentSize(1610612736) // 1.5 GB
	if got != "1.5 GB" {
		t.Errorf("FormatTorrentSize(1.5GB) = %q, want %q", got, "1.5 GB")
	}
}

func TestFormatTorrentSizeRU(t *testing.T) {
	msg, _ := Load("ru")
	got := msg.FormatTorrentSize(1610612736) // 1.5 GB
	if got != "1.5 ГБ" {
		t.Errorf("FormatTorrentSize(1.5GB) = %q, want %q", got, "1.5 ГБ")
	}
}

func TestAllSimpleFieldsNotEmpty(t *testing.T) {
	for _, locale := range []string{"en", "ru"} {
		msg, _ := Load(locale)
		fields := map[string]string{
			"AccessPending":           msg.AccessPending,
			"AccessRequestSent":       msg.AccessRequestSent,
			"AccessApproved":          msg.AccessApproved,
			"AccessDenied":            msg.AccessDenied,
			"AccessRestored":          msg.AccessRestored,
			"AccessNoPermission":      msg.AccessNoPermission,
			"AccessError":             msg.AccessError,
			"AccessUpdateError":       msg.AccessUpdateError,
			"AccessUnblockError":      msg.AccessUnblockError,
			"AccessRevokeError":       msg.AccessRevokeError,
			"AccessListError":         msg.AccessListError,
			"AccessNoDenied":          msg.AccessNoDenied,
			"AccessNoApproved":        msg.AccessNoApproved,
			"AccessFallbackName":      msg.AccessFallbackName,
			"AccessBtnApprove":        msg.AccessBtnApprove,
			"AccessBtnDeny":           msg.AccessBtnDeny,
			"AccessBtnUnblock":        msg.AccessBtnUnblock,
			"AccessBtnRevoke":         msg.AccessBtnRevoke,
			"AccessRequestNamePrefix": msg.AccessRequestNamePrefix,
			"SearchEmpty":             msg.SearchEmpty,
			"SearchCacheMiss":         msg.SearchCacheMiss,
			"DownloadSendError":       msg.DownloadSendError,
			"DownloadTorrentWait":     msg.DownloadTorrentWait,
			"DownloadRTNotFound":      msg.DownloadRTNotFound,
			"DownloadRTError":         msg.DownloadRTError,
			"DownloadTorrentErr":      msg.DownloadTorrentErr,
			"HealthNoProviders":       msg.HealthNoProviders,
			"HealthTitle":             msg.HealthTitle,
			"BroadcastUsage":          msg.BroadcastUsage,
			"BroadcastUserListErr":    msg.BroadcastUserListErr,
			"NavBack":                 msg.NavBack,
			"NavNext":                 msg.NavNext,
			"ErrNotFound":             msg.ErrNotFound,
			"ErrFormatNA":             msg.ErrFormatNA,
			"ErrFileTooLarge":         msg.ErrFileTooLarge,
			"ErrTimeout":              msg.ErrTimeout,
			"ErrProvider":             msg.ErrProvider,
			"ErrBookUnavailable":      msg.ErrBookUnavailable,
			"ErrNoSeeders":            msg.ErrNoSeeders,
			"ErrTorrentTooLarge":      msg.ErrTorrentTooLarge,
			"ErrServiceDown":          msg.ErrServiceDown,
			"ErrUnexpected":           msg.ErrUnexpected,
			"ErrRTNoSeeders":          msg.ErrRTNoSeeders,
			"ErrRTTorrentTooLarge":    msg.ErrRTTorrentTooLarge,
			"ErrRTTimeout":            msg.ErrRTTimeout,
			"ErrRTFormatNA":           msg.ErrRTFormatNA,
			"ErrRTServiceDown":        msg.ErrRTServiceDown,
			"ErrRTDownload":           msg.ErrRTDownload,
		}
		for name, val := range fields {
			if val == "" {
				t.Errorf("[%s] %s is empty", locale, name)
			}
		}
	}
}

func TestAllFunctionsNotNil(t *testing.T) {
	for _, locale := range []string{"en", "ru"} {
		msg, _ := Load(locale)
		funcs := map[string]any{
			"StartAdmin":          msg.StartAdmin,
			"StartUser":           msg.StartUser,
			"HelpText":            msg.HelpText,
			"FoundBooks":          msg.FoundBooks,
			"FormatFileSize":      msg.FormatFileSize,
			"FormatTorrentSize":   msg.FormatTorrentSize,
			"SeedsLabel":          msg.SeedsLabel,
			"TorrentPicked":       msg.TorrentPicked,
			"FileSendError":       msg.FileSendError,
			"AccessApprovedFor":   msg.AccessApprovedFor,
			"AccessDeniedFor":     msg.AccessDeniedFor,
			"AccessRevokedFor":    msg.AccessRevokedFor,
			"AccessRequestNotify": msg.AccessRequestNotify,
			"ProviderError":       msg.ProviderError,
			"BroadcastTitle":      msg.BroadcastTitle,
			"BroadcastComplete":   msg.BroadcastComplete,
			"SettingsText":        msg.SettingsText,
		}
		for name, fn := range funcs {
			if fn == nil {
				t.Errorf("[%s] %s is nil", locale, name)
			}
		}
	}
}
