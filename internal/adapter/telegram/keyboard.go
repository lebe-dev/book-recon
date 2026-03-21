package telegram

import (
	"fmt"
	"strconv"
	"strings"

	"github.com/lebe-dev/book-recon/internal/domain"
	"gopkg.in/telebot.v4"
)

func buildResultsText(query string, results []domain.SearchResult, offset, total int) string {
	var sb strings.Builder
	fmt.Fprintf(&sb, "🔍 *%s*\n\n", escapeMarkdown(query))
	sb.WriteString(foundText(total))
	sb.WriteString(". Выберите книгу для скачивания:")

	// Group results by provider, preserving order of first appearance.
	type providerGroup struct {
		name    string
		indices []int
	}
	var groups []providerGroup
	providerIdx := make(map[string]int)

	for i, r := range results {
		idx, ok := providerIdx[r.Book.Provider]
		if !ok {
			idx = len(groups)
			providerIdx[r.Book.Provider] = idx
			groups = append(groups, providerGroup{name: r.Book.Provider})
		}
		groups[idx].indices = append(groups[idx].indices, i)
	}

	for _, g := range groups {
		fmt.Fprintf(&sb, "\n\n*%s*", escapeMarkdown(g.name))

		for _, i := range g.indices {
			r := results[i]
			num := offset + i + 1
			title := escapeMarkdown(r.Book.Title)
			author := escapeMarkdown(r.Book.Author)

			sb.WriteString("\n")
			if author != "" {
				fmt.Fprintf(&sb, "*%d.* %s — %s\n", num, title, author)
			} else {
				fmt.Fprintf(&sb, "*%d.* %s\n", num, title)
			}

			formats := formatList(r.Book.Formats)
			fmt.Fprintf(&sb, "      📄 %s", formats)

			// RuTracker: show seeds and torrent size.
			if r.Book.Provider == "RuTracker" && r.Book.Metadata != nil {
				seeds := r.Book.Metadata["seeds"]
				torrentSize := r.Book.Metadata["torrent_size"]
				if seeds != "" || torrentSize != "" {
					sb.WriteString("\n      ")
					if seeds != "" {
						fmt.Fprintf(&sb, "🌱 %s сида", seeds)
					}
					if torrentSize != "" {
						if seeds != "" {
							sb.WriteString(" · ")
						}
						if size, err := strconv.ParseInt(torrentSize, 10, 64); err == nil {
							fmt.Fprintf(&sb, "📦 %s", formatTorrentSize(size))
						}
					}
				}
			}
		}
	}

	return sb.String()
}

func formatTorrentSize(bytes int64) string {
	const (
		kb = 1024
		mb = 1024 * kb
		gb = 1024 * mb
	)
	switch {
	case bytes >= gb:
		return fmt.Sprintf("%.1f ГБ", float64(bytes)/float64(gb))
	case bytes >= mb:
		return fmt.Sprintf("%.1f МБ", float64(bytes)/float64(mb))
	case bytes >= kb:
		return fmt.Sprintf("%.0f КБ", float64(bytes)/float64(kb))
	default:
		return fmt.Sprintf("%d Б", bytes)
	}
}

func buildResultsKeyboard(results []domain.SearchResult, offset int, hasMore bool, total int) *telebot.ReplyMarkup {
	markup := &telebot.ReplyMarkup{}

	var rows []telebot.Row

	var dlBtns []telebot.Btn
	for i, r := range results {
		num := offset + i + 1
		dlBtns = append(dlBtns, markup.Data(fmt.Sprintf("%d", num), "dl", r.ID))
	}
	if len(dlBtns) > 0 {
		row := make(telebot.Row, len(dlBtns))
		copy(row, dlBtns)
		rows = append(rows, row)
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

	type fmtBtn struct {
		label  string
		format domain.Format
	}
	row1Btns := []fmtBtn{
		{"EPUB", domain.FormatEPUB},
		{"FB2", domain.FormatFB2},
		{"MOBI", domain.FormatMOBI},
	}
	row2Btns := []fmtBtn{
		{"PDF", domain.FormatPDF},
		{"DJVU", domain.FormatDJVU},
	}

	makeRow := func(btns []fmtBtn) telebot.Row {
		var row telebot.Row
		for _, b := range btns {
			label := "○ " + b.label
			if current == b.format {
				label = "● " + b.label
			}
			row = append(row, markup.Data(label, "fmt", string(b.format)))
		}
		return row
	}

	markup.Inline(makeRow(row1Btns), makeRow(row2Btns))
	return markup
}

func buildFormatKeyboard(resultID string, formats []domain.Format) *telebot.ReplyMarkup {
	markup := &telebot.ReplyMarkup{}

	var row telebot.Row
	for _, f := range formats {
		label := strings.ToUpper(string(f))
		row = append(row, markup.Data(label, "dlf", resultID, string(f)))
	}

	markup.Inline(row)
	return markup
}

func formatList(formats []domain.Format) string {
	if len(formats) == 0 {
		return ""
	}
	var parts []string
	for _, f := range formats {
		parts = append(parts, strings.ToUpper(string(f)))
	}
	return strings.Join(parts, ", ")
}
