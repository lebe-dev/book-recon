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

	results, err := b.service.Search(c.Get("ctx").(contextKey).ctx, c.Sender().ID, query)
	if err != nil {
		return b.handleError(c, err)
	}

	_, hasMore, _ := b.service.GetPage(c.Get("ctx").(contextKey).ctx, c.Sender().ID, 0)

	text := fmt.Sprintf("🔍 Результаты поиска по запросу: *%s*\nВыберите книгу для скачивания:", escapeMarkdown(query))
	return c.Send(text, buildResultsKeyboard(results, 0, hasMore), telebot.ModeMarkdown)
}

func (b *Bot) handleDownload(c telebot.Context) error {
	resultID := c.Data()
	if resultID == "" {
		return nil
	}

	if err := c.Notify(telebot.UploadingDocument); err != nil {
		b.logger.Warn("failed to send typing action", "error", err)
	}

	tmpPath, filename, err := b.service.Download(c.Get("ctx").(contextKey).ctx, c.Sender().ID, resultID)
	if err != nil {
		return b.handleError(c, err)
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

	return c.Send(doc)
}

func (b *Bot) handlePage(c telebot.Context) error {
	offsetStr := c.Data()
	offset, err := strconv.Atoi(offsetStr)
	if err != nil || offset < 0 {
		b.logger.Warn("invalid page offset", "data", offsetStr)
		return nil
	}

	results, hasMore, err := b.service.GetPage(c.Get("ctx").(contextKey).ctx, c.Sender().ID, offset)
	if err != nil {
		return b.handleError(c, err)
	}

	return c.Edit(buildResultsKeyboard(results, offset, hasMore))
}

func (b *Bot) handleError(c telebot.Context, err error) error {
	code, ok := domain.ErrorCodeFrom(err)
	if !ok {
		b.logger.Error("unhandled error", "error", err)
		return c.Send("Произошла непредвиденная ошибка. Попробуйте позже.")
	}

	msg := errorMessage(code)
	return c.Send(msg)
}

func errorMessage(code domain.ErrorCode) string {
	switch code {
	case domain.ErrCodeNotFound:
		return "Книга не найдена. Попробуйте новый поиск."
	case domain.ErrCodeFormatNA:
		return "Формат недоступен для этой книги."
	case domain.ErrCodeFileTooLarge:
		return "Файл слишком большой (>50 MB)."
	case domain.ErrCodeTimeout:
		return "Источник не отвечает. Попробуйте позже."
	case domain.ErrCodeProviderError:
		return "Ошибка при обращении к источнику."
	default:
		return "Произошла ошибка. Попробуйте позже."
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
