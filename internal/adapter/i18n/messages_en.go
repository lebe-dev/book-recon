package i18n

import "fmt"

func loadEN() *Messages {
	return &Messages{
		// --- Access Control ---
		AccessPending:           "⏳ Your access request is pending.",
		AccessRequestSent:       "📨 Access request sent to admins. Please wait.",
		AccessApproved:          "Your access request has been approved. Type a book title to search.",
		AccessDenied:            "Your access request has been denied.",
		AccessRestored:          "Your access has been restored. Type a book title to search.",
		AccessNoPermission:      "No permission",
		AccessError:             "Error",
		AccessUpdateError:       "Error updating",
		AccessUnblockError:      "Error unblocking",
		AccessRevokeError:       "Error revoking",
		AccessListError:         "Error getting list.",
		AccessNoDenied:          "No blocked users.",
		AccessNoApproved:        "No approved users.",
		AccessFallbackName:      "user",
		AccessBtnApprove:        "✅ Approve",
		AccessBtnDeny:           "❌ Deny",
		AccessBtnUnblock:        "Unblock",
		AccessBtnRevoke:         "Revoke access",
		AccessUnblocked:         "\n\n✅ Unblocked",
		AccessRequestNamePrefix: "Name: ",

		// --- Search ---
		SearchEmpty:        "Enter a book title or author name to search.",
		SearchCacheMiss:    "Book not found in cache. Repeat the search.",
		SearchChooseFormat: "\nChoose format:",
		SearchSelectBook:   ". Select a book to download:",

		// --- Download ---
		DownloadSendError:   "Error sending file.",
		DownloadTorrentWait: "⏳ Downloading torrent, this may take a few minutes...",
		DownloadRTChooseFmt: "\nChoose format (torrent download may take several minutes):",
		DownloadRTNotFound:  "⚠️ RuTracker provider not found.",
		DownloadRTError:     "⚠️ RuTracker provider error.",
		DownloadTorrentErr:  "⚠️ Error downloading torrent.",
		DownloadSourceLabel: "\n\nSource: ",

		// --- Health ---
		HealthNoProviders: "No providers with health check support.",
		HealthTitle:       "🏥 *Service Status*\n\n",

		// --- Broadcast ---
		BroadcastUsage:       "Usage:\n/whats\\_new _message text_",
		BroadcastUserListErr: "Error getting user list.",

		// --- Navigation ---
		NavBack: "← Back",
		NavNext: "Next →",

		// --- Error Messages ---
		ErrNotFound:        "📭 No books found. Try a different query or check the spelling.",
		ErrFormatNA:        "📄 This format is not available for this book. Change format in /settings.",
		ErrFileTooLarge:    "📦 File is too large (>50 MB) — Telegram does not accept such files.",
		ErrTimeout:         "⏱ Source is not responding. Try again in a few minutes.",
		ErrProvider:        "⚠️ Error contacting source. Try again later.",
		ErrBookUnavailable: "🚫 Book is unavailable for download (removed due to a copyright claim).",
		ErrNoSeeders:       "🌱 No seeders — download is not possible. Try another torrent.",
		ErrTorrentTooLarge: "📦 Torrent is too large. Maximum: 2 GB.",
		ErrServiceDown:     "⚠️ Search service is unavailable. Try again later.",
		ErrUnexpected:      "⚠️ Unexpected error. Try again later.",

		// --- RuTracker errors ---
		ErrRTNoSeeders:       "🌱 No seeders — download is not possible. Try another torrent.",
		ErrRTTorrentTooLarge: "📦 Torrent is too large. Maximum: 2 GB.",
		ErrRTTimeout:         "⏱ Torrent download timed out. Try a torrent with more seeders.",
		ErrRTFormatNA:        "📄 No files of the requested format found in the torrent. Try another format.",
		ErrRTServiceDown:     "⚠️ Search service is unavailable. Try again later.",
		ErrRTDownload:        "⚠️ Error downloading torrent. Try again later.",

		// --- Parameterized messages ---
		StartAdmin: func(version string) string {
			return fmt.Sprintf("📚 *Book Recon* `%s`", version)
		},
		StartUser: func(name string) string {
			return fmt.Sprintf("📚 *Book Recon*\n\nHello, %s! Type a book title or author name — I'll find and download it.\n\nCommands:\n/settings — format settings\n/help — help", name)
		},
		HelpText: func(isAdmin bool) string {
			text := "📖 *Help*\n\n" +
				"Type a book title or author name — the bot will find and offer to download it.\n\n" +
				"*Tips:*\n" +
				"• Search runs across multiple sources simultaneously\n" +
				"• Results are shown 5 at a time, use buttons to navigate\n" +
				"• Available formats are shown on buttons\n\n" +
				"Formats: EPUB, FB2\n\n" +
				"/settings — choose preferred format"
			if isAdmin {
				text += "\n\n*Administration:*\n" +
					"/allowed\\_users — approved users\n" +
					"/blocked\\_users — blocked users\n" +
					"/whats\\_new — broadcast \"What's New\" to all users\n" +
					"/health — service status"
			}
			return text
		},
		FoundBooks: func(n int) string {
			if n == 1 {
				return "Found 1 book"
			}
			return fmt.Sprintf("Found %d books", n)
		},
		FormatFileSize: func(bytes int64) string {
			const (
				kb = 1024
				mb = 1024 * kb
			)
			switch {
			case bytes >= mb:
				return fmt.Sprintf("%.1f MB", float64(bytes)/float64(mb))
			case bytes >= kb:
				return fmt.Sprintf("%.0f KB", float64(bytes)/float64(kb))
			default:
				return fmt.Sprintf("%d B", bytes)
			}
		},
		FormatTorrentSize: func(bytes int64) string {
			const (
				kb = 1024
				mb = 1024 * kb
				gb = 1024 * mb
			)
			switch {
			case bytes >= gb:
				return fmt.Sprintf("%.1f GB", float64(bytes)/float64(gb))
			case bytes >= mb:
				return fmt.Sprintf("%.1f MB", float64(bytes)/float64(mb))
			case bytes >= kb:
				return fmt.Sprintf("%.0f KB", float64(bytes)/float64(kb))
			default:
				return fmt.Sprintf("%d B", bytes)
			}
		},
		SeedsLabel: func(seeds string) string {
			return fmt.Sprintf("🌱 %s seeds", seeds)
		},
		TorrentPicked: func(count int, format string) string {
			if count == 1 {
				return fmt.Sprintf("📚 Picked %d file from torrent (%s):", count, format)
			}
			return fmt.Sprintf("📚 Picked %d files from torrent (%s):", count, format)
		},
		FileSendError: func(name string) string {
			return fmt.Sprintf("⚠️ Failed to send file %s.", name)
		},
		AccessApprovedFor: func(name string) string {
			return fmt.Sprintf("✅ Access approved for %s", name)
		},
		AccessDeniedFor: func(name string) string {
			return fmt.Sprintf("❌ Access denied for %s", name)
		},
		AccessRevokedFor: func(name string) string {
			return "🚫 Access revoked for user " + name
		},
		AccessRequestNotify: func(id int64, name, username string) string {
			text := fmt.Sprintf("🔔 *Access Request*\n\nID: `%d`\nName: %s", id, name)
			if username != "" {
				text += fmt.Sprintf("\nUsername: @%s", username)
			}
			return text
		},
		ProviderError: func(provider, errMsg string) string {
			return fmt.Sprintf("⚠️ *Provider error: %s*\n\n`%s`", provider, errMsg)
		},
		BroadcastTitle: func(text string) string {
			return fmt.Sprintf("📢 *What's New*\n\n%s", text)
		},
		BroadcastComplete: func(sent, failed int) string {
			return fmt.Sprintf("Broadcast complete: sent %d, errors %d.", sent, failed)
		},
		SettingsText: func(format string) string {
			return fmt.Sprintf("⚙️ Settings\n\nPreferred format: *%s*", format)
		},
	}
}
