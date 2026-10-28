package telegram

import (
	"fmt"
	"io"
	"strings"
	"testing"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/adapter/i18n"
	"github.com/lebe-dev/book-recon/internal/domain"
	"github.com/lebe-dev/book-recon/internal/usecase"
	"gopkg.in/telebot.v4"
)

func newAudioTestBot(t *testing.T) *Bot {
	t.Helper()
	msg, _ := i18n.Load("ru")
	svc := usecase.NewAudiobookService(nil, nil, nil, nil, usecase.AudiobookConfig{
		MaxDownloadSize: 4 << 30,
		PublicURL:       "https://audio.example.com",
	}, log.New(io.Discard))
	return &Bot{msg: msg, audio: svc, logger: log.New(io.Discard)}
}

func allButtons(kb *telebot.ReplyMarkup) []telebot.InlineButton {
	var out []telebot.InlineButton
	for _, row := range kb.InlineKeyboard {
		out = append(out, row...)
	}
	return out
}

func TestRenderSelection(t *testing.T) {
	b := newAudioTestBot(t)
	sel := &usecase.AudiobookSelection{
		ID:          "deadbeef",
		Title:       "Толстой <Война и мир>",
		Selected:    map[int]bool{},
		MaxFileSize: 1000,
	}
	for i := 0; i < 23; i++ {
		dir := "CD1"
		if i >= 12 {
			dir = "CD2"
		}
		sel.Files = append(sel.Files, domain.TorrentFile{Index: i, Path: fmt.Sprintf("%s/Глава %02d.mp3", dir, i+1), Size: 100})
	}
	sel.Files[1].Size = 5000 // too large
	sel.Selected[0] = true

	text, kb := b.renderSelection(sel)
	for _, want := range []string{"Толстой &lt;Война и мир&gt;", "1. ✅ Глава 01.mp3", "2. 🚫 Глава 02.mp3", "📁 <i>CD1</i>", "Выбрано: <b>1</b>"} {
		if !strings.Contains(text, want) {
			t.Errorf("text does not contain %q:\n%s", want, text)
		}
	}
	if strings.Contains(text, "Глава 11") {
		t.Error("first page shows more than 10 files")
	}

	var labels []string
	for _, btn := range allButtons(kb) {
		labels = append(labels, btn.Text)
		if data := "\f" + btn.Unique + "|" + btn.Data; len(data) > 64 {
			t.Errorf("callback data too long (%d): %q", len(data), data)
		}
	}
	joined := strings.Join(labels, ",")
	for _, want := range []string{"✅1", "🚫2", "1 / 3", "📁 Папки", "⬇️ Скачать (1)"} {
		if !strings.Contains(joined, want) {
			t.Errorf("buttons %v do not contain %q", labels, want)
		}
	}

	// Last page, folder view.
	sel.Page = 2
	text, _ = b.renderSelection(sel)
	if !strings.Contains(text, "23. ⬜ Глава 23.mp3") {
		t.Errorf("last page:\n%s", text)
	}
	sel.FolderView = true
	sel.Page = 0
	text, kb = b.renderSelection(sel)
	if !strings.Contains(text, "1. ◩ 📁 CD1 — 1/12") || !strings.Contains(text, "2. ⬜ 📁 CD2 — 0/11") {
		t.Errorf("folder view:\n%s", text)
	}
	if !strings.Contains(fmt.Sprint(allButtons(kb)), "📄 Файлы") {
		t.Error("folder view lacks the files button")
	}
}

func TestRenderAudioResultsPaging(t *testing.T) {
	b := newAudioTestBot(t)
	sess := &usecase.AudiobookSearchSession{Query: "<q>"}
	for i := 0; i < 12; i++ {
		sess.Results = append(sess.Results, domain.NewAudiobook(domain.Audiobook{
			Title: fmt.Sprintf("Book %d", i+1), TorrentURL: fmt.Sprint(i), Size: 1 << 30, Seeders: 3,
		}))
	}

	text, kb := b.renderAudioResults(sess, 10)
	if !strings.Contains(text, "&lt;q&gt;") || !strings.Contains(text, "<b>11.</b> Book 11") || strings.Contains(text, "Book 10") {
		t.Errorf("text:\n%s", text)
	}
	btns := allButtons(kb)
	if btns[0].Unique != "abr" || btns[0].Data != sess.Results[10].ID {
		t.Errorf("first button = %+v", btns[0])
	}
	var nav []string
	for _, btn := range btns[2:] {
		nav = append(nav, btn.Text+"="+btn.Data)
	}
	if got := strings.Join(nav, ","); got != "← Назад=5,3 / 3=" {
		t.Errorf("nav = %s", got)
	}
}

func TestRenderProgress(t *testing.T) {
	b := newAudioTestBot(t)
	text := b.renderProgress(domain.AudiobookJob{
		Title:    "Книга",
		Progress: domain.DownloadProgress{Completed: 50 << 20, Total: 200 << 20, Speed: 1 << 20, Peers: 4},
	})
	for _, want := range []string{"25%", "▓▓░░░░░░░░", "50.0 МБ из 200.0 МБ", "1.0 МБ/с", "пиров: 4", "2 мин 30 сек"} {
		if !strings.Contains(text, want) {
			t.Errorf("progress does not contain %q:\n%s", want, text)
		}
	}
}

func TestTruncateAndPlain(t *testing.T) {
	if got := truncate("абвгд", 3); got != "аб…" {
		t.Errorf("truncate = %q", got)
	}
	if got := plain("<b>a &amp; b</b>"); got != "a & b" {
		t.Errorf("plain = %q", got)
	}
}
