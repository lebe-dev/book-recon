package i18n

import (
	"fmt"
	"strings"
	"time"
)

func audioEN() AudioMessages {
	return AudioMessages{
		Help:             "/audio _query_ — search audiobooks on RuTracker",
		AdminHelp:        "/audiobooks — turn audiobooks on/off",
		Usage:            "🎧 Usage: <code>/audio title or author</code>",
		DisabledEnv:      "🎧 Audiobooks are not available on this server.",
		Disabled:         "🎧 Audiobooks are temporarily disabled by the admin.",
		NotFound:         "📭 No audiobooks found. Try another query.",
		SessionExpired:   "⌛ Session expired. Search again with /audio.",
		NoAudioFiles:     "🔇 No audio files in this torrent. Pick another one.",
		LoadingTorrent:   "⏳ Loading the file list...",
		SelectionEmpty:   "No files selected",
		SelectionClosed:  "File selection cancelled.",
		FileTooLargeMark: "🚫",
		RootFolder:       "(torrent root)",
		NoDownloads:      "You have no active downloads or ready links.",
		ActiveHeader:     "<b>Downloads:</b>",
		ReadyHeader:      "<b>Ready:</b>",
		AdminEnvOff:      "🎧 Audiobooks are disabled by <code>AUDIOBOOKS_ENABLED=false</code>. They can't be enabled from the bot.",
		ToggleError:      "Failed to save the setting",

		BtnAll:            "✅ All",
		BtnNone:           "⬜ Clear",
		BtnFolders:        "📁 Folders",
		BtnFiles:          "📄 Files",
		BtnCancel:         "✖ Cancel",
		BtnCancelDownload: "✖ Cancel download",
		BtnEnable:         "✅ Enable",
		BtnDisable:        "⛔ Disable",

		ErrServiceDown: "⚠️ Search service is unavailable. Try again later.",
		ErrStalled:     "the torrent stopped downloading (no data from peers)",
		ErrQuota:       "💾 The server is out of audiobook storage. Try again later, when old links expire.",
		ErrUserLimit:   "⏳ You already have an active download. Wait for it or cancel it.",
		ErrAlready:     "⏳ This torrent is already being downloaded. Try again a bit later.",
		ErrTimeout:     "it didn't finish in the allotted time",
		ErrNoSeeders:   "no seeders",
		ErrInterrupted: "the bot was restarted during the download, please start it again",
		ErrDownload:    "download error",
		ErrSelectionBig: func(limit string) string {
			return fmt.Sprintf("Selection exceeds the limit (%s)", limit)
		},

		WebFiles:       "Files",
		WebDownloadAll: "Download all (zip)",
		WebDownload:    "download",
		WebExpires:     "available until",

		Found: func(query string, n int) string {
			return fmt.Sprintf("🎧 <b>%s</b>\n\nFound %d torrents. Pick one:", query, n)
		},
		ResultLine: func(num int, title, size, seeds string) string {
			return fmt.Sprintf("<b>%d.</b> %s\n      📦 %s · 🌱 %s", num, title, size, seeds)
		},
		Selection: func(title string, files int, total string) string {
			return fmt.Sprintf("🎧 <b>%s</b>\nAudio files: %d · %s", title, files, total)
		},
		SelectionSum: func(count int, size, limit string) string {
			s := fmt.Sprintf("Selected: <b>%d</b> · <b>%s</b>", count, size)
			if limit != "" {
				s += fmt.Sprintf(" (limit %s)", limit)
			}
			return s
		},
		FolderLine: func(num int, mark, name string, selected, files int, size string) string {
			return fmt.Sprintf("%d. %s 📁 %s — %d/%d · %s", num, mark, name, selected, files, size)
		},
		DownloadBtn: func(count int) string {
			return fmt.Sprintf("⬇️ Download (%d)", count)
		},
		Queued: func(title string, pos, files int, size string) string {
			return fmt.Sprintf("⏳ <b>%s</b>\nQueued: #%d. Files: %d · %s\nI'll let you know when it starts.", title, pos, files, size)
		},
		Progress: func(title string, percent int, done, total, speed string, peers int, eta string) string {
			s := fmt.Sprintf("⬇️ <b>%s</b>\n%s %d%%\n%s of %s · %s/s · peers: %d", title, progressBar(percent), percent, done, total, speed, peers)
			if eta != "" {
				s += "\nRemaining ≈ " + eta
			}
			return s
		},
		Started: func(title string) string {
			return fmt.Sprintf("▶️ Download started: <b>%s</b>", title)
		},
		Ready: func(title, link, ttl string, files int, size string) string {
			return fmt.Sprintf("✅ Audiobook is ready: <b>%s</b>\nFiles: %d · %s\n\n🔗 %s\n\nThe link is valid for %s.", title, files, size, link, ttl)
		},
		ReadyStatus: func(title string) string {
			return fmt.Sprintf("✅ <b>%s</b> — downloaded.", title)
		},
		FailedStatus: func(title string) string {
			return fmt.Sprintf("❌ <b>%s</b> — not downloaded.", title)
		},
		Failed: func(title, reason string) string {
			return fmt.Sprintf("❌ Failed to download <b>%s</b>: %s.", title, reason)
		},
		Cancelled: func(title string) string {
			return fmt.Sprintf("✖ Download cancelled: <b>%s</b>", title)
		},
		ActiveLine: func(title, state string) string {
			return fmt.Sprintf("• %s — %s", title, state)
		},
		ReadyLine: func(title, link, ttl string) string {
			return fmt.Sprintf("• %s\n  🔗 %s (%s left)", title, link, ttl)
		},
		QueuedState: func(pos int) string {
			return fmt.Sprintf("queued (#%d)", pos)
		},
		AdminStatus: func(enabled bool, active, queued, ready int, used, max string) string {
			state := "✅ enabled"
			if !enabled {
				state = "⛔ disabled"
			}
			return fmt.Sprintf("🎧 <b>Audiobooks</b>: %s\n\nDownloading: %d · queued: %d\nReady links: %d\nDisk: %s of %s", state, active, queued, ready, used, max)
		},
		Duration: func(d time.Duration) string {
			return formatDuration(d, "h", "min", "s")
		},
	}
}

// progressBar renders a 10-cell bar.
func progressBar(percent int) string {
	percent = max(0, min(100, percent))
	filled := percent / 10
	return strings.Repeat("▓", filled) + strings.Repeat("░", 10-filled)
}

// formatDuration renders the two most significant units, e.g. "3 h 5 min".
// Hours are not rolled up into days: "48 h" reads better for link lifetimes.
func formatDuration(d time.Duration, hour, minute, second string) string {
	if d < time.Second {
		d = time.Second
	}
	parts := []struct {
		n    int64
		unit string
	}{
		{int64(d / time.Hour), hour},
		{int64(d % time.Hour / time.Minute), minute},
		{int64(d % time.Minute / time.Second), second},
	}
	var out []string
	for _, p := range parts {
		if p.n == 0 && len(out) == 0 {
			continue
		}
		if len(out) == 2 {
			break
		}
		if p.n > 0 {
			out = append(out, fmt.Sprintf("%d %s", p.n, p.unit))
		} else {
			// Keep the slot so "1 h 0 min" doesn't turn into "1 h 12 s".
			out = append(out, "")
		}
	}
	return strings.TrimSpace(strings.Join(out, " "))
}
