package i18n

import "fmt"

// Messages holds all user-facing strings for a single locale.
type Messages struct {
	// --- Access Control ---
	AccessPending           string
	AccessRequestSent       string
	AccessApproved          string
	AccessDenied            string
	AccessRestored          string
	AccessNoPermission      string
	AccessError             string
	AccessUpdateError       string
	AccessUnblockError      string
	AccessRevokeError       string
	AccessListError         string
	AccessNoDenied          string
	AccessNoApproved        string
	AccessFallbackName      string
	AccessBtnApprove        string
	AccessBtnDeny           string
	AccessBtnUnblock        string
	AccessBtnRevoke         string
	AccessUnblocked         string
	AccessRequestNamePrefix string // used for both generation and parsing ("Имя: " / "Name: ")

	// --- Search ---
	SearchEmpty        string
	SearchCacheMiss    string
	SearchChooseFormat string
	SearchSelectBook   string

	// --- Download ---
	DownloadSendError   string
	DownloadTorrentWait string
	DownloadRTChooseFmt string
	DownloadRTNotFound  string
	DownloadRTError     string
	DownloadTorrentErr  string
	DownloadSourceLabel string

	// --- Health ---
	HealthNoProviders string
	HealthTitle       string

	// --- Broadcast ---
	BroadcastUsage       string
	BroadcastUserListErr string

	// --- Navigation ---
	NavBack string
	NavNext string

	// --- Error Messages (domain errors) ---
	ErrNotFound        string
	ErrFormatNA        string
	ErrFileTooLarge    string
	ErrTimeout         string
	ErrProvider        string
	ErrBookUnavailable string
	ErrNoSeeders       string
	ErrTorrentTooLarge string
	ErrServiceDown     string
	ErrUnexpected      string

	// --- RuTracker errors ---
	ErrRTNoSeeders       string
	ErrRTTorrentTooLarge string
	ErrRTTimeout         string
	ErrRTFormatNA        string
	ErrRTServiceDown     string
	ErrRTDownload        string

	// --- Parameterized messages ---
	StartAdmin          func(version string) string
	StartUser           func(name string) string
	HelpText            func(isAdmin bool) string
	FoundBooks          func(n int) string
	FormatFileSize      func(bytes int64) string
	FormatTorrentSize   func(bytes int64) string
	SeedsLabel          func(seeds string) string
	TorrentPicked       func(count int, format string) string
	FileSendError       func(name string) string
	AccessApprovedFor   func(name string) string
	AccessDeniedFor     func(name string) string
	AccessRevokedFor    func(name string) string
	AccessRequestNotify func(id int64, name, username string) string
	ProviderError       func(provider, errMsg string) string
	BroadcastTitle      func(text string) string
	BroadcastComplete   func(sent, failed int) string
	SettingsText        func(format string) string
}

// Load returns the Messages for the given locale.
func Load(locale string) (*Messages, error) {
	switch locale {
	case "en":
		return loadEN(), nil
	case "ru":
		return loadRU(), nil
	default:
		return nil, fmt.Errorf("unsupported locale: %s", locale)
	}
}
