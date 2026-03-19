package telegram

import (
	"fmt"

	"github.com/lebe-dev/book-recon/internal/domain"
	"gopkg.in/telebot.v4"
)

func buildResultsKeyboard(results []domain.SearchResult, offset int, hasMore bool) *telebot.ReplyMarkup {
	markup := &telebot.ReplyMarkup{}

	var rows []telebot.Row
	for _, r := range results {
		label := r.Book.Title
		if r.Book.Author != "" {
			label = fmt.Sprintf("%s — %s", r.Book.Title, r.Book.Author)
		}
		if len(label) > 60 {
			label = label[:57] + "..."
		}

		rows = append(rows, markup.Row(
			markup.Data(label, "dl", r.ID),
		))
	}

	var navBtns []telebot.Btn
	if offset > 0 {
		navBtns = append(navBtns, markup.Data("← Назад", "page", fmt.Sprintf("%d", offset-pageSize)))
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

	epubLabel := "EPUB"
	fb2Label := "FB2"
	if current == domain.FormatEPUB {
		epubLabel = "✓ EPUB"
	} else {
		fb2Label = "✓ FB2"
	}

	markup.Inline(
		markup.Row(
			markup.Data(epubLabel, "fmt", string(domain.FormatEPUB)),
			markup.Data(fb2Label, "fmt", string(domain.FormatFB2)),
		),
	)

	return markup
}
