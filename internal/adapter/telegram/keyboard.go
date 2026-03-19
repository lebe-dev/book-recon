package telegram

import (
	"fmt"
	"strings"

	"github.com/lebe-dev/book-recon/internal/domain"
	"gopkg.in/telebot.v4"
)

func buildResultsKeyboard(results []domain.SearchResult, offset int, hasMore bool, total int) *telebot.ReplyMarkup {
	markup := &telebot.ReplyMarkup{}

	var rows []telebot.Row
	for _, r := range results {
		label := r.Book.Title
		if r.Book.Author != "" {
			label = fmt.Sprintf("%s — %s", r.Book.Title, r.Book.Author)
		}
		badge := formatBadge(r.Book.Formats)
		maxLen := 57 - len(badge)
		if len(label) > maxLen {
			label = label[:maxLen] + "..."
		}
		label = label + badge

		rows = append(rows, markup.Row(
			markup.Data(label, "dl", r.ID),
		))
	}

	var navBtns []telebot.Btn
	if offset > 0 {
		navBtns = append(navBtns, markup.Data("← Назад", "page", fmt.Sprintf("%d", offset-pageSize)))
	}
	if total > pageSize {
		currentPage := offset/pageSize + 1
		totalPages := (total + pageSize - 1) / pageSize
		navBtns = append(navBtns, markup.Data(fmt.Sprintf("%d / %d", currentPage, totalPages), "noop", ""))
	}
	if hasMore {
		navBtns = append(navBtns, markup.Data("Далее →", "page", fmt.Sprintf("%d", offset+pageSize)))
	}
	if len(navBtns) > 0 {
		row := make(telebot.Row, len(navBtns))
		copy(row, navBtns)
		rows = append(rows, row)
	}

	markup.Inline(rows...)
	return markup
}

func buildSettingsKeyboard(current domain.Format) *telebot.ReplyMarkup {
	markup := &telebot.ReplyMarkup{}

	epubLabel := "○ EPUB"
	fb2Label := "○ FB2"
	if current == domain.FormatEPUB {
		epubLabel = "● EPUB"
	} else {
		fb2Label = "● FB2"
	}

	markup.Inline(
		markup.Row(
			markup.Data(epubLabel, "fmt", string(domain.FormatEPUB)),
			markup.Data(fb2Label, "fmt", string(domain.FormatFB2)),
		),
	)

	return markup
}

func formatBadge(formats []domain.Format) string {
	if len(formats) == 0 {
		return ""
	}
	var parts []string
	for _, f := range formats {
		parts = append(parts, strings.ToUpper(string(f)))
	}
	return " [" + strings.Join(parts, ", ") + "]"
}
