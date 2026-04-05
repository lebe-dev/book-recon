package telegram

import (
	"context"
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/lebe-dev/book-recon/internal/adapter/provider/rutracker"
	"github.com/lebe-dev/book-recon/internal/domain"
	"gopkg.in/telebot.v4"
)

func (b *Bot) handleSearch(c telebot.Context) error {
	query := strings.TrimSpace(c.Text())
	if query == "" {
		return c.Send(b.msg.SearchEmpty)
	}

	if strings.HasPrefix(query, "/") {
		return nil
	}

	b.logger.Debug("search request", "username", c.Sender().Username, "query", query)

	results, err := b.service.Search(c.Get("ctx").(contextKey).ctx, c.Sender().ID, query)
	if err != nil {
		return b.handleError(c, err)
	}

	_, hasMore, total, _ := b.service.GetPage(c.Get("ctx").(contextKey).ctx, c.Sender().ID, 0)

	text := buildResultsText(b.msg, query, results, 0, total)
	return c.Send(text, buildResultsKeyboard(b.msg, results, 0, hasMore, total), telebot.ModeMarkdown)
}

func (b *Bot) handleDownload(c telebot.Context) error {
	resultID := c.Data()
	if resultID == "" {
		return nil
	}

	ctx := c.Get("ctx").(contextKey).ctx
	b.logger.Debug("book selected", "username", c.Sender().Username, "result_id", resultID)

	result, err := b.service.GetResult(ctx, c.Sender().ID, resultID)
	if err != nil {
		return b.handleError(c, err)
	}
	if result == nil {
		return c.Respond(&telebot.CallbackResponse{Text: b.msg.SearchCacheMiss})
	}

	title := escapeMarkdown(result.Book.Title)
	author := escapeMarkdown(result.Book.Author)

	var sb strings.Builder
	if author != "" {
		fmt.Fprintf(&sb, "📖 *%s* — %s\n", title, author)
	} else {
		fmt.Fprintf(&sb, "📖 *%s*\n", title)
	}

	// RuTracker: show seeds/size and torrent warning.
	if result.Book.Provider == "RuTracker" && result.Book.Metadata != nil {
		seeds := result.Book.Metadata["seeds"]
		torrentSize := result.Book.Metadata["torrent_size"]
		if seeds != "" || torrentSize != "" {
			parts := make([]string, 0, 2)
			if seeds != "" {
				parts = append(parts, b.msg.SeedsLabel(seeds))
			}
			if torrentSize != "" {
				if size, err := strconv.ParseInt(torrentSize, 10, 64); err == nil {
					parts = append(parts, fmt.Sprintf("📦 %s", b.msg.FormatFileSize(size)))
				}
			}
			sb.WriteString(strings.Join(parts, " · "))
			sb.WriteString("\n")
		}
		sb.WriteString(b.msg.DownloadRTChooseFmt)
	} else {
		sb.WriteString(b.msg.SearchChooseFormat)
	}

	return c.Edit(sb.String(), buildFormatKeyboard(resultID, result.Book.Formats), telebot.ModeMarkdown)
}

func (b *Bot) handleDownloadFormat(c telebot.Context) error {
	data := c.Data()
	parts := strings.SplitN(data, "|", 2)
	if len(parts) != 2 {
		return nil
	}

	resultID := parts[0]
	format := domain.Format(parts[1])

	ctx := c.Get("ctx").(contextKey).ctx
	b.logger.Debug("download request", "username", c.Sender().Username, "result_id", resultID, "format", format)

	result, err := b.service.GetResult(ctx, c.Sender().ID, resultID)
	if err != nil {
		return b.handleError(c, err)
	}
	if result == nil {
		return c.Respond(&telebot.CallbackResponse{Text: b.msg.SearchCacheMiss})
	}

	// RuTracker: async download in goroutine.
	if result.Book.Provider == "RuTracker" {
		go b.downloadRuTracker(c, result, format)
		return c.Edit(b.msg.DownloadTorrentWait)
	}

	// Other providers: synchronous download.
	var sourceURL string
	if result != nil {
		sourceURL = result.Book.SourceURL
	}

	if err := c.Notify(telebot.UploadingDocument); err != nil {
		b.logger.Warn("failed to send typing action", "error", err)
	}

	tmpPath, filename, fileSize, err := b.service.DownloadWithFormat(ctx, c.Sender().ID, resultID, format)
	if err != nil {
		return b.handleDownloadError(c, err, sourceURL)
	}
	defer func() { _ = os.Remove(tmpPath) }()

	f, err := os.Open(tmpPath)
	if err != nil {
		return c.Send(b.msg.DownloadSendError)
	}
	defer func() { _ = f.Close() }()

	doc := &telebot.Document{
		File:     telebot.FromReader(f),
		FileName: filename,
		Caption:  fmt.Sprintf("📦 %s", b.msg.FormatFileSize(fileSize)),
	}

	b.logger.Debug("sending document", "username", c.Sender().Username, "filename", filename, "size", fileSize)
	return c.Send(doc)
}

// downloadRuTracker handles async torrent download and file sending.
func (b *Bot) downloadRuTracker(c telebot.Context, result *domain.SearchResult, format domain.Format) {
	ctx := context.Background()
	chatID := c.Chat().ID

	provider, ok := b.service.GetProvider("RuTracker")
	if !ok {
		b.sendToChat(chatID, b.msg.DownloadRTNotFound)
		return
	}

	rtProvider, ok := provider.(*rutracker.Provider)
	if !ok {
		b.sendToChat(chatID, b.msg.DownloadRTError)
		return
	}

	b.logger.Debug("rutracker async download started", "username", c.Sender().Username, "result_id", result.ID)

	picked, cleanup, err := rtProvider.DownloadMulti(ctx, *result, format)
	if err != nil {
		code, ok := domain.ErrorCodeFrom(err)
		if ok {
			b.sendToChat(chatID, b.rutrackerErrorMessage(code))
		} else {
			b.logger.Error("rutracker download failed", "error", err)
			b.sendToChat(chatID, b.msg.DownloadTorrentErr)
		}
		return
	}
	defer cleanup()

	// Update status message.
	b.sendToChat(chatID, b.msg.TorrentPicked(len(picked), strings.ToUpper(string(picked[0].Format))))

	// Send each file.
	for _, pf := range picked {
		f, err := os.Open(pf.Path)
		if err != nil {
			b.logger.Error("failed to open picked file", "path", pf.Path, "error", err)
			b.sendToChat(chatID, b.msg.FileSendError(pf.Name))
			continue
		}

		doc := &telebot.Document{
			File:     telebot.FromReader(f),
			FileName: pf.Name,
			Caption:  fmt.Sprintf("📦 %s", b.msg.FormatFileSize(pf.Size)),
		}

		if _, err := b.bot.Send(telebot.ChatID(chatID), doc); err != nil {
			b.logger.Error("failed to send document", "filename", pf.Name, "error", err)
			b.sendToChat(chatID, b.msg.FileSendError(pf.Name))
		}
		_ = f.Close()
	}

	b.logger.Info("rutracker download complete", "username", c.Sender().Username, "files", len(picked))
}

func (b *Bot) sendToChat(chatID int64, text string) {
	if _, err := b.bot.Send(telebot.ChatID(chatID), text); err != nil {
		b.logger.Error("failed to send message", "chat_id", chatID, "error", err)
	}
}

func (b *Bot) rutrackerErrorMessage(code domain.ErrorCode) string {
	switch code {
	case domain.ErrCodeNoSeeders:
		return b.msg.ErrRTNoSeeders
	case domain.ErrCodeTorrentTooLarge:
		return b.msg.ErrRTTorrentTooLarge
	case domain.ErrCodeTimeout:
		return b.msg.ErrRTTimeout
	case domain.ErrCodeFormatNA:
		return b.msg.ErrRTFormatNA
	case domain.ErrCodeServiceDown:
		return b.msg.ErrRTServiceDown
	default:
		return b.msg.ErrRTDownload
	}
}

func (b *Bot) handlePage(c telebot.Context) error {
	offsetStr := c.Data()
	offset, err := strconv.Atoi(offsetStr)
	if err != nil || offset < 0 {
		b.logger.Warn("invalid page offset", "data", offsetStr)
		return nil
	}

	b.logger.Debug("page request", "username", c.Sender().Username, "offset", offset)

	results, hasMore, total, err := b.service.GetPage(c.Get("ctx").(contextKey).ctx, c.Sender().ID, offset)
	if err != nil {
		return b.handleError(c, err)
	}

	query := extractQuery(c.Message().Text)
	text := buildResultsText(b.msg, query, results, offset, total)
	return c.Edit(text, buildResultsKeyboard(b.msg, results, offset, hasMore, total), telebot.ModeMarkdown)
}

func extractQuery(text string) string {
	firstLine, _, _ := strings.Cut(text, "\n")
	return strings.TrimPrefix(firstLine, "🔍 ")
}

func (b *Bot) handleError(c telebot.Context, err error) error {
	code, ok := domain.ErrorCodeFrom(err)
	if !ok {
		b.logger.Error("unhandled error", "username", c.Sender().Username, "error", err)
		return c.Send(b.msg.ErrUnexpected)
	}

	b.logger.Debug("domain error", "username", c.Sender().Username, "code", code, "error", err)
	return c.Send(b.errorMessage(code))
}

func (b *Bot) handleDownloadError(c telebot.Context, err error, sourceURL string) error {
	code, ok := domain.ErrorCodeFrom(err)
	if !ok {
		b.logger.Error("unhandled error", "username", c.Sender().Username, "error", err)
		return c.Send(b.msg.ErrUnexpected)
	}

	b.logger.Debug("domain error", "username", c.Sender().Username, "code", code, "error", err)
	msg := b.errorMessage(code)
	if sourceURL != "" {
		msg += b.msg.DownloadSourceLabel + sourceURL
	}
	return c.Send(msg)
}

func (b *Bot) errorMessage(code domain.ErrorCode) string {
	switch code {
	case domain.ErrCodeNotFound:
		return b.msg.ErrNotFound
	case domain.ErrCodeFormatNA:
		return b.msg.ErrFormatNA
	case domain.ErrCodeFileTooLarge:
		return b.msg.ErrFileTooLarge
	case domain.ErrCodeTimeout:
		return b.msg.ErrTimeout
	case domain.ErrCodeProviderError:
		return b.msg.ErrProvider
	case domain.ErrCodeBookUnavailable:
		return b.msg.ErrBookUnavailable
	case domain.ErrCodeNoSeeders:
		return b.msg.ErrNoSeeders
	case domain.ErrCodeTorrentTooLarge:
		return b.msg.ErrTorrentTooLarge
	case domain.ErrCodeServiceDown:
		return b.msg.ErrServiceDown
	default:
		return b.msg.ErrUnexpected
	}
}

func escapeMarkdown(s string) string {
	replacer := strings.NewReplacer(
		"_", "\\_",
		"*", "\\*",
		"[", "\\[",
		"]", "\\]",
		"`", "\\`",
	)
	return replacer.Replace(s)
}
