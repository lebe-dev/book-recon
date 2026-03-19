package telegram

import (
	"fmt"
	"os"
	"strconv"
	"strings"

	"github.com/lebe-dev/book-recon/internal/domain"
	"gopkg.in/telebot.v4"
)

func (b *Bot) handleSearch(c telebot.Context) error {
	query := strings.TrimSpace(c.Text())
	if query == "" {
		return c.Send("Введите название книги или имя автора для поиска.")
	}

	b.logger.Debug("search request", "username", c.Sender().Username, "query", query)

	results, err := b.service.Search(c.Get("ctx").(contextKey).ctx, c.Sender().ID, query)
	if err != nil {
		return b.handleError(c, err)
	}

	_, hasMore, total, _ := b.service.GetPage(c.Get("ctx").(contextKey).ctx, c.Sender().ID, 0)

	text := fmt.Sprintf("🔍 *%s*\n\n%s. Выберите книгу для скачивания:", escapeMarkdown(query), foundText(total))
	return c.Send(text, buildResultsKeyboard(results, 0, hasMore, total), telebot.ModeMarkdown)
}

func (b *Bot) handleDownload(c telebot.Context) error {
	resultID := c.Data()
	if resultID == "" {
		return nil
	}

	ctx := c.Get("ctx").(contextKey).ctx
	b.logger.Debug("download request", "username", c.Sender().Username, "result_id", resultID)

	result, err := b.service.GetResult(ctx, c.Sender().ID, resultID)
	if err != nil {
		return b.handleError(c, err)
	}

	var sourceURL string
	if result != nil {
		sourceURL = result.Book.SourceURL
	}

	if err := c.Notify(telebot.UploadingDocument); err != nil {
		b.logger.Warn("failed to send typing action", "error", err)
	}

	tmpPath, filename, err := b.service.Download(ctx, c.Sender().ID, resultID)
	if err != nil {
		return b.handleDownloadError(c, err, sourceURL)
	}
	defer func() { _ = os.Remove(tmpPath) }()

	f, err := os.Open(tmpPath)
	if err != nil {
		return c.Send("Ошибка при отправке файла.")
	}
	defer func() { _ = f.Close() }()

	doc := &telebot.Document{
		File:     telebot.FromReader(f),
		FileName: filename,
	}

	b.logger.Debug("sending document", "username", c.Sender().Username, "filename", filename)
	return c.Send(doc)
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

	return c.Edit(buildResultsKeyboard(results, offset, hasMore, total))
}

func (b *Bot) handleError(c telebot.Context, err error) error {
	code, ok := domain.ErrorCodeFrom(err)
	if !ok {
		b.logger.Error("unhandled error", "username", c.Sender().Username, "error", err)
		return c.Send("Произошла непредвиденная ошибка. Попробуйте позже.")
	}

	b.logger.Debug("domain error", "username", c.Sender().Username, "code", code, "error", err)
	return c.Send(errorMessage(code))
}

func (b *Bot) handleDownloadError(c telebot.Context, err error, sourceURL string) error {
	code, ok := domain.ErrorCodeFrom(err)
	if !ok {
		b.logger.Error("unhandled error", "username", c.Sender().Username, "error", err)
		return c.Send("Произошла непредвиденная ошибка. Попробуйте позже.")
	}

	b.logger.Debug("domain error", "username", c.Sender().Username, "code", code, "error", err)
	msg := errorMessage(code)
	if sourceURL != "" {
		msg += "\n\nИсточник: " + sourceURL
	}
	return c.Send(msg)
}

func errorMessage(code domain.ErrorCode) string {
	switch code {
	case domain.ErrCodeNotFound:
		return "📭 Книги не найдены. Попробуйте другой запрос или проверьте написание."
	case domain.ErrCodeFormatNA:
		return "📄 Этот формат недоступен для книги. Измените формат в /settings."
	case domain.ErrCodeFileTooLarge:
		return "📦 Файл слишком большой (>50 МБ) — Telegram не принимает такие файлы."
	case domain.ErrCodeTimeout:
		return "⏱ Источник не отвечает. Попробуйте через несколько минут."
	case domain.ErrCodeProviderError:
		return "⚠️ Ошибка при обращении к источнику. Попробуйте позже."
	case domain.ErrCodeBookUnavailable:
		return "🚫 Книга недоступна для скачивания (удалена по жалобе правообладателя)."
	default:
		return "⚠️ Непредвиденная ошибка. Попробуйте позже."
	}
}

func foundText(n int) string {
	switch {
	case n == 1:
		return "Найдена 1 книга"
	case n >= 2 && n <= 4:
		return fmt.Sprintf("Найдено %d книги", n)
	default:
		return fmt.Sprintf("Найдено %d книг", n)
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
