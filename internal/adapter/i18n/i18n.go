package i18n

import (
	"fmt"
	"time"
)

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
	StartAdmin           func(version string) string
	StartUser            func(name string) string
	HelpText             func(isAdmin bool) string
	FoundBooks           func(n int) string
	FormatFileSize       func(bytes int64) string
	FormatTorrentSize    func(bytes int64) string
	SeedsLabel           func(seeds string) string
	TorrentPicked        func(count int, format string) string
	FileSendError        func(name string) string
	AccessApprovedFor    func(name string) string
	AccessDeniedFor      func(name string) string
	AccessRevokedFor     func(name string) string
	AccessRequestNotify  func(id int64, name, username string) string
	ProviderError        func(provider, errMsg string) string
	BroadcastTitle       func(text string) string
	BroadcastComplete    func(sent, failed int) string
	SettingsText         func(format string) string
	ErrSourceUnavailable func(provider string) string

	// --- Audiobooks ---
	Audio AudioMessages
}

// AudioMessages holds audiobook strings. Messages are rendered in HTML mode:
// callers pass already escaped titles and file names.
type AudioMessages struct {
	Help             string
	AdminHelp        string
	Usage            string
	DisabledEnv      string
	Disabled         string
	NotFound         string
	SessionExpired   string
	NoAudioFiles     string
	LoadingTorrent   string
	SelectionEmpty   string
	SelectionClosed  string
	FileTooLargeMark string
	RootFolder       string
	NoDownloads      string
	ActiveHeader     string
	ReadyHeader      string
	AdminEnvOff      string
	ToggleError      string

	BtnAll            string
	BtnNone           string
	BtnFolders        string
	BtnFiles          string
	BtnCancel         string
	BtnCancelDownload string
	BtnEnable         string
	BtnDisable        string

	ErrServiceDown  string
	ErrStalled      string
	ErrQuota        string
	ErrUserLimit    string
	ErrAlready      string
	ErrTimeout      string
	ErrNoSeeders    string
	ErrInterrupted  string
	ErrDownload     string
	ErrSelectionBig func(limit string) string

	WebFiles       string
	WebDownloadAll string
	WebExpires     string
	WebDownload    string

	Found        func(query string, n int) string
	ResultLine   func(num int, title, size, seeds string) string
	Selection    func(title string, files int, total string) string
	SelectionSum func(count int, size, limit string) string
	FolderLine   func(num int, mark, name string, selected, files int, size string) string
	DownloadBtn  func(count int) string
	Queued       func(title string, pos, files int, size string) string
	Progress     func(title string, percent int, done, total, speed string, peers int, eta string) string
	Started      func(title string) string
	Ready        func(title, link, ttl string, files int, size string) string
	ReadyStatus  func(title string) string
	FailedStatus func(title string) string
	Failed       func(title, reason string) string
	Cancelled    func(title string) string
	ActiveLine   func(title, state string) string
	ReadyLine    func(title, link, ttl string) string
	QueuedState  func(pos int) string
	AdminStatus  func(enabled bool, active, queued, ready int, used, max string) string
	Duration     func(d time.Duration) string
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
