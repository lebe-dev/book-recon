package telegram

import (
	"errors"
	"fmt"
	"html"
	"path"
	"strconv"
	"strings"
	"time"

	"github.com/lebe-dev/book-recon/internal/domain"
	"github.com/lebe-dev/book-recon/internal/usecase"
	"gopkg.in/telebot.v4"
)

const (
	audioResultsPageSize   = 5
	audioSelectionPageSize = 10
	audioTitleMaxLen       = 150
	audioFileNameMaxLen    = 60
)

// SetAudiobookService enables audiobook handlers. Without it the commands
// reply that the feature is unavailable.
func (b *Bot) SetAudiobookService(svc *usecase.AudiobookService) {
	b.audio = svc
	svc.SetNotifier(b)
}

func (b *Bot) handleAudio(c telebot.Context) error {
	if b.audio == nil {
		return c.Send(b.msg.Audio.DisabledEnv)
	}
	if !b.audio.Enabled() {
		return c.Send(b.msg.Audio.Disabled)
	}

	query := strings.TrimSpace(c.Message().Payload)
	if query == "" {
		return c.Send(b.renderMyDownloads(c.Sender().ID), telebot.ModeHTML, telebot.NoPreview)
	}

	b.logger.Debug("audiobook search", "username", c.Sender().Username, "query", query)
	if err := c.Notify(telebot.Typing); err != nil {
		b.logger.Warn("failed to send typing action", "error", err)
	}

	sess, err := b.audio.Search(c.Get("ctx").(contextKey).ctx, c.Sender().ID, query)
	if err != nil {
		return c.Send(b.audioErrorText(err), telebot.ModeHTML)
	}

	text, kb := b.renderAudioResults(sess, 0)
	return c.Send(text, kb, telebot.ModeHTML)
}

func (b *Bot) handleAudioPage(c telebot.Context) error {
	if b.audio == nil {
		return c.Respond()
	}
	offset, err := strconv.Atoi(c.Data())
	if err != nil || offset < 0 {
		return c.Respond()
	}
	sess, ok := b.audio.GetSearch(c.Sender().ID)
	if !ok {
		return c.Respond(&telebot.CallbackResponse{Text: plain(b.msg.Audio.SessionExpired), ShowAlert: true})
	}
	text, kb := b.renderAudioResults(sess, offset)
	return b.ignoreNotModified(c.Edit(text, kb, telebot.ModeHTML))
}

// handleAudioResult opens the file picker for a search result in a new
// message, so the result list stays available.
func (b *Bot) handleAudioResult(c telebot.Context) error {
	if b.audio == nil {
		return c.Respond()
	}
	_ = c.Respond()

	msg, err := b.bot.Send(c.Chat(), b.msg.Audio.LoadingTorrent)
	if err != nil {
		return err
	}

	sel, err := b.audio.OpenSelection(c.Get("ctx").(contextKey).ctx, c.Sender().ID, c.Data())
	if err != nil {
		_, err = b.bot.Edit(msg, b.audioErrorText(err), telebot.ModeHTML)
		return err
	}

	text, kb := b.renderSelection(sel)
	_, err = b.bot.Edit(msg, text, kb, telebot.ModeHTML)
	return err
}

func (b *Bot) handleAudioSelection(c telebot.Context) error {
	if b.audio == nil {
		return c.Respond()
	}
	parts := strings.SplitN(c.Data(), "|", 3)
	if len(parts) < 2 {
		return c.Respond()
	}
	sid, op := parts[0], parts[1]
	var arg int
	if len(parts) == 3 {
		arg, _ = strconv.Atoi(parts[2])
	}
	userID := c.Sender().ID

	switch op {
	case "x":
		b.audio.CloseSelection(userID, sid)
		_ = c.Respond()
		return c.Edit(b.msg.Audio.SelectionClosed)
	case "go":
		return b.startAudioDownload(c, sid)
	}

	sel, err := b.audio.UpdateSelection(userID, sid, func(s *usecase.AudiobookSelection) {
		switch op {
		case "t":
			s.ToggleFile(arg)
		case "f":
			s.ToggleFolder(arg)
		case "a":
			s.SelectAll()
		case "n":
			s.SelectNone()
		case "p":
			s.Page = arg
		case "v":
			s.FolderView = !s.FolderView
			s.Page = 0
		}
	})
	if err != nil {
		return c.Respond(&telebot.CallbackResponse{Text: plain(b.audioErrorText(err)), ShowAlert: true})
	}

	if op == "t" && arg >= 0 && arg < len(sel.Files) && !sel.Selectable(sel.Files[arg]) {
		limit := b.msg.FormatTorrentSize(sel.MaxFileSize)
		_ = c.Respond(&telebot.CallbackResponse{Text: b.msg.Audio.ErrSelectionBig(limit), ShowAlert: true})
	} else {
		_ = c.Respond()
	}

	text, kb := b.renderSelection(sel)
	return b.ignoreNotModified(c.Edit(text, kb, telebot.ModeHTML))
}

func (b *Bot) startAudioDownload(c telebot.Context, sid string) error {
	ctx := c.Get("ctx").(contextKey).ctx
	_, err := b.audio.StartDownload(ctx, c.Sender().ID, c.Chat().ID, c.Message().ID, sid)
	if err != nil {
		var text string
		switch code, _ := domain.ErrorCodeFrom(err); code {
		case domain.ErrCodeSelectionEmpty:
			text = b.msg.Audio.SelectionEmpty
		case domain.ErrCodeSelectionTooLarge:
			text = b.msg.Audio.ErrSelectionBig(b.msg.FormatTorrentSize(b.audio.Config().MaxDownloadSize))
		default:
			text = plain(b.audioErrorText(err))
		}
		return c.Respond(&telebot.CallbackResponse{Text: text, ShowAlert: true})
	}
	// The status message is updated by OnAudiobookEvent.
	return c.Respond()
}

func (b *Bot) handleAudioCancelJob(c telebot.Context) error {
	if b.audio == nil {
		return c.Respond()
	}
	isAdmin := b.isAdmin(strings.ToLower(c.Sender().Username))
	if err := b.audio.CancelJob(c.Sender().ID, c.Data(), isAdmin); err != nil {
		return c.Respond(&telebot.CallbackResponse{Text: plain(b.msg.Audio.SessionExpired)})
	}
	return c.Respond()
}

func (b *Bot) handleAudiobooksAdmin(c telebot.Context) error {
	if !b.isAdmin(strings.ToLower(c.Sender().Username)) {
		return nil
	}
	if b.audio == nil {
		return c.Send(b.msg.Audio.AdminEnvOff, telebot.ModeHTML)
	}
	text, kb := b.renderAudioAdmin()
	return c.Send(text, kb, telebot.ModeHTML)
}

func (b *Bot) handleAudioToggle(c telebot.Context) error {
	if !b.isAdmin(strings.ToLower(c.Sender().Username)) || b.audio == nil {
		return c.Respond()
	}
	ctx := c.Get("ctx").(contextKey).ctx
	if err := b.audio.SetEnabled(ctx, c.Data() == "on"); err != nil {
		b.logger.Error("failed to toggle audiobooks", "error", err)
		return c.Respond(&telebot.CallbackResponse{Text: b.msg.Audio.ToggleError, ShowAlert: true})
	}
	_ = c.Respond()
	text, kb := b.renderAudioAdmin()
	return b.ignoreNotModified(c.Edit(text, kb, telebot.ModeHTML))
}

// OnAudiobookEvent implements usecase.AudiobookNotifier. Intermediate states
// edit the status message silently; final states also send a new message so
// the user gets a notification.
func (b *Bot) OnAudiobookEvent(kind domain.AudiobookEventKind, job domain.AudiobookJob) {
	m := b.msg.Audio
	title := html.EscapeString(truncate(job.Title, audioTitleMaxLen))

	switch kind {
	case domain.AudiobookEventQueued:
		b.editStatus(job, m.Queued(title, job.QueuePosition, job.FileCount, b.msg.FormatTorrentSize(job.TotalSize)), b.cancelJobKeyboard(job.ID))
	case domain.AudiobookEventStarted:
		b.editStatus(job, b.renderProgress(job), b.cancelJobKeyboard(job.ID))
		if job.WasQueued {
			b.sendHTML(job.ChatID, m.Started(title))
		}
	case domain.AudiobookEventProgress:
		b.editStatus(job, b.renderProgress(job), b.cancelJobKeyboard(job.ID))
	case domain.AudiobookEventCompleted:
		b.editStatus(job, m.ReadyStatus(title), nil)
		ttl := m.Duration(time.Until(job.ExpiresAt).Round(time.Minute))
		link := html.EscapeString(b.audio.LinkFor(job.ID))
		b.sendHTML(job.ChatID, m.Ready(title, link, ttl, job.FileCount, b.msg.FormatTorrentSize(job.TotalSize)))
	case domain.AudiobookEventFailed, domain.AudiobookEventInterrupted:
		b.editStatus(job, m.FailedStatus(title), nil)
		b.sendHTML(job.ChatID, m.Failed(title, b.audioFailureReason(job.Err)))
	case domain.AudiobookEventCancelled:
		b.editStatus(job, m.Cancelled(title), nil)
	}
}

func (b *Bot) editStatus(job domain.AudiobookJob, text string, kb *telebot.ReplyMarkup) {
	if job.StatusMessageID == 0 {
		return
	}
	ref := &telebot.StoredMessage{MessageID: strconv.Itoa(job.StatusMessageID), ChatID: job.ChatID}
	// Without reply markup Telegram drops the inline keyboard.
	opts := []any{telebot.ModeHTML}
	if kb != nil {
		opts = append(opts, kb)
	}
	if _, err := b.bot.Edit(ref, text, opts...); b.ignoreNotModified(err) != nil {
		b.logger.Warn("failed to update audiobook status", "job", job.ID, "error", err)
	}
}

func (b *Bot) sendHTML(chatID int64, text string) {
	if _, err := b.bot.Send(telebot.ChatID(chatID), text, telebot.ModeHTML, telebot.NoPreview); err != nil {
		b.logger.Error("failed to send message", "chat_id", chatID, "error", err)
	}
}

func (b *Bot) ignoreNotModified(err error) error {
	if errors.Is(err, telebot.ErrSameMessageContent) || errors.Is(err, telebot.ErrMessageNotModified) {
		return nil
	}
	return err
}

func (b *Bot) renderAudioResults(sess *usecase.AudiobookSearchSession, offset int) (string, *telebot.ReplyMarkup) {
	total := len(sess.Results)
	if offset >= total {
		offset = (total - 1) / audioResultsPageSize * audioResultsPageSize
	}
	end := min(offset+audioResultsPageSize, total)

	var sb strings.Builder
	sb.WriteString(b.msg.Audio.Found(html.EscapeString(sess.Query), total))

	markup := &telebot.ReplyMarkup{}
	var btns telebot.Row
	for i, r := range sess.Results[offset:end] {
		num := offset + i + 1
		sb.WriteString("\n\n")
		sb.WriteString(b.msg.Audio.ResultLine(num, html.EscapeString(truncate(r.Title, audioTitleMaxLen)),
			b.msg.FormatTorrentSize(r.Size), strconv.Itoa(r.Seeders)))
		btns = append(btns, markup.Data(strconv.Itoa(num), "abr", r.ID))
	}

	rows := []telebot.Row{btns}
	if nav := b.navRow(markup, "abp", "", offset/audioResultsPageSize, pageCount(total, audioResultsPageSize), audioResultsPageSize); nav != nil {
		rows = append(rows, nav)
	}
	markup.Inline(rows...)
	return sb.String(), markup
}

func (b *Bot) renderSelection(sel *usecase.AudiobookSelection) (string, *telebot.ReplyMarkup) {
	m := b.msg.Audio
	markup := &telebot.ReplyMarkup{}
	folders := sel.Folders()

	var sb strings.Builder
	sb.WriteString(m.Selection(html.EscapeString(truncate(sel.Title, audioTitleMaxLen)), len(sel.Files), b.msg.FormatTorrentSize(sel.TotalSize())))
	sb.WriteString("\n")

	itemCount := len(sel.Files)
	if sel.FolderView {
		itemCount = len(folders)
	}
	pages := pageCount(itemCount, audioSelectionPageSize)
	page := max(0, min(sel.Page, pages-1))
	start := page * audioSelectionPageSize
	end := min(start+audioSelectionPageSize, itemCount)

	var toggles []telebot.Btn
	if sel.FolderView {
		for i := start; i < end; i++ {
			f := folders[i]
			selected, size := sel.FolderState(f)
			name := f.Path
			if name == "" {
				name = m.RootFolder
			}
			sb.WriteString("\n")
			sb.WriteString(m.FolderLine(i+1, selectionMark(selected, len(f.Files)), html.EscapeString(truncate(name, audioFileNameMaxLen)),
				selected, len(f.Files), b.msg.FormatTorrentSize(size)))
			toggles = append(toggles, markup.Data(toggleLabel(i+1, selected > 0, true), "abs", sel.ID, "f", strconv.Itoa(i)))
		}
	} else {
		lastDir := "\x00"
		for i := start; i < end; i++ {
			f := sel.Files[i]
			if dir := path.Dir(f.Path); len(folders) > 1 && dir != lastDir {
				lastDir = dir
				name := dir
				if dir == "." {
					name = m.RootFolder
				}
				fmt.Fprintf(&sb, "\n📁 <i>%s</i>", html.EscapeString(truncate(name, audioFileNameMaxLen)))
			}
			selected := sel.Selected[f.Index]
			mark := "⬜"
			switch {
			case selected:
				mark = "✅"
			case !sel.Selectable(f):
				mark = m.FileTooLargeMark
			}
			fmt.Fprintf(&sb, "\n%d. %s %s — %s", i+1, mark,
				html.EscapeString(truncate(path.Base(f.Path), audioFileNameMaxLen)), b.msg.FormatTorrentSize(f.Size))
			toggles = append(toggles, markup.Data(toggleLabel(i+1, selected, sel.Selectable(f)), "abs", sel.ID, "t", strconv.Itoa(i)))
		}
	}

	limit := ""
	if maxSize := b.audio.Config().MaxDownloadSize; maxSize > 0 {
		limit = b.msg.FormatTorrentSize(maxSize)
	}
	sb.WriteString("\n\n")
	sb.WriteString(m.SelectionSum(sel.SelectedCount(), b.msg.FormatTorrentSize(sel.SelectedSize()), limit))

	var rows []telebot.Row
	for i := 0; i < len(toggles); i += 5 {
		rows = append(rows, toggles[i:min(i+5, len(toggles))])
	}
	if nav := b.navRow(markup, "abs", sel.ID+"|p", page, pages, 1); nav != nil {
		rows = append(rows, nav)
	}

	actions := telebot.Row{
		markup.Data(m.BtnAll, "abs", sel.ID, "a"),
		markup.Data(m.BtnNone, "abs", sel.ID, "n"),
	}
	if len(folders) > 1 {
		label := m.BtnFolders
		if sel.FolderView {
			label = m.BtnFiles
		}
		actions = append(actions, markup.Data(label, "abs", sel.ID, "v"))
	}
	rows = append(rows, actions, telebot.Row{
		markup.Data(m.DownloadBtn(sel.SelectedCount()), "abs", sel.ID, "go"),
		markup.Data(m.BtnCancel, "abs", sel.ID, "x"),
	})

	markup.Inline(rows...)
	return sb.String(), markup
}

// navRow builds "← n/N →". Callback data is prefix|value where value is
// page*step (an offset for search results, a page number for selections).
func (b *Bot) navRow(markup *telebot.ReplyMarkup, unique, prefix string, page, pages, step int) telebot.Row {
	if pages <= 1 {
		return nil
	}
	data := func(p int) []string {
		v := strconv.Itoa(p * step)
		if prefix == "" {
			return []string{v}
		}
		return []string{prefix, v}
	}
	var row telebot.Row
	if page > 0 {
		row = append(row, markup.Data(b.msg.NavBack, unique, data(page-1)...))
	}
	row = append(row, markup.Data(fmt.Sprintf("%d / %d", page+1, pages), "noop", ""))
	if page < pages-1 {
		row = append(row, markup.Data(b.msg.NavNext, unique, data(page+1)...))
	}
	return row
}

func (b *Bot) renderProgress(job domain.AudiobookJob) string {
	p := job.Progress
	total := p.Total
	if total == 0 {
		total = job.TotalSize
	}
	percent := 0
	if total > 0 {
		percent = int(p.Completed * 100 / total)
	}
	eta := ""
	if p.Speed > 0 && total > p.Completed {
		eta = b.msg.Audio.Duration(time.Duration((total-p.Completed)/p.Speed) * time.Second)
	}
	title := html.EscapeString(truncate(job.Title, audioTitleMaxLen))
	return b.msg.Audio.Progress(title, percent, b.msg.FormatTorrentSize(p.Completed), b.msg.FormatTorrentSize(total),
		b.msg.FormatTorrentSize(p.Speed), p.Peers, eta)
}

func (b *Bot) renderMyDownloads(userID int64) string {
	m := b.msg.Audio
	active, ready := b.audio.UserDownloads(userID)

	var sb strings.Builder
	sb.WriteString(m.Usage)
	if len(active) == 0 && len(ready) == 0 {
		sb.WriteString("\n\n")
		sb.WriteString(m.NoDownloads)
		return sb.String()
	}
	if len(active) > 0 {
		sb.WriteString("\n\n")
		sb.WriteString(m.ActiveHeader)
		for _, j := range active {
			state := m.QueuedState(j.QueuePosition)
			if j.Status == domain.AudiobookJobDownloading {
				state = "0%"
				if j.Progress.Total > 0 {
					state = fmt.Sprintf("%d%%", j.Progress.Completed*100/j.Progress.Total)
				}
			}
			sb.WriteString("\n")
			sb.WriteString(m.ActiveLine(html.EscapeString(truncate(j.Title, audioTitleMaxLen)), state))
		}
	}
	if len(ready) > 0 {
		sb.WriteString("\n\n")
		sb.WriteString(m.ReadyHeader)
		for _, d := range ready {
			sb.WriteString("\n")
			sb.WriteString(m.ReadyLine(html.EscapeString(truncate(d.Title, audioTitleMaxLen)),
				html.EscapeString(b.audio.LinkFor(d.ID)), m.Duration(time.Until(d.ExpiresAt).Round(time.Minute))))
		}
	}
	return sb.String()
}

func (b *Bot) renderAudioAdmin() (string, *telebot.ReplyMarkup) {
	st := b.audio.Stats()
	enabled := b.audio.Enabled()
	maxSize := "∞"
	if st.MaxBytes > 0 {
		maxSize = b.msg.FormatTorrentSize(st.MaxBytes)
	}
	text := b.msg.Audio.AdminStatus(enabled, st.Active, st.Queued, st.Ready, b.msg.FormatTorrentSize(st.UsedBytes), maxSize)

	markup := &telebot.ReplyMarkup{}
	btn := markup.Data(b.msg.Audio.BtnDisable, "abt", "off")
	if !enabled {
		btn = markup.Data(b.msg.Audio.BtnEnable, "abt", "on")
	}
	markup.Inline(telebot.Row{btn})
	return text, markup
}

func (b *Bot) cancelJobKeyboard(jobID string) *telebot.ReplyMarkup {
	markup := &telebot.ReplyMarkup{}
	markup.Inline(telebot.Row{markup.Data(b.msg.Audio.BtnCancelDownload, "abj", jobID)})
	return markup
}

// audioErrorText maps errors of user actions (search, opening a torrent,
// starting a download) to messages.
func (b *Bot) audioErrorText(err error) string {
	m := b.msg.Audio
	code, ok := domain.ErrorCodeFrom(err)
	if !ok {
		b.logger.Error("audiobook error", "error", err)
		return b.msg.ErrUnexpected
	}
	switch code {
	case domain.ErrCodeFeatureDisabled:
		return m.Disabled
	case domain.ErrCodeNotFound:
		return m.NotFound
	case domain.ErrCodeSessionExpired:
		return m.SessionExpired
	case domain.ErrCodeNoAudioFiles:
		return m.NoAudioFiles
	case domain.ErrCodeServiceDown:
		return m.ErrServiceDown
	case domain.ErrCodeQuotaExceeded:
		return m.ErrQuota
	case domain.ErrCodeUserLimitReached:
		return m.ErrUserLimit
	case domain.ErrCodeAlreadyDownloading:
		return m.ErrAlready
	case domain.ErrCodeSelectionEmpty:
		return m.SelectionEmpty
	default:
		b.logger.Error("audiobook error", "error", err)
		return b.msg.ErrUnexpected
	}
}

// audioFailureReason explains why a running download failed.
func (b *Bot) audioFailureReason(err error) string {
	m := b.msg.Audio
	code, _ := domain.ErrorCodeFrom(err)
	switch code {
	case domain.ErrCodeStalled:
		return m.ErrStalled
	case domain.ErrCodeTimeout:
		return m.ErrTimeout
	case domain.ErrCodeNoSeeders:
		return m.ErrNoSeeders
	case domain.ErrCodeInterrupted:
		return m.ErrInterrupted
	case domain.ErrCodeAlreadyDownloading:
		return plain(m.ErrAlready)
	default:
		return m.ErrDownload
	}
}

func selectionMark(selected, total int) string {
	switch selected {
	case 0:
		return "⬜"
	case total:
		return "✅"
	default:
		return "◩"
	}
}

func toggleLabel(num int, selected, selectable bool) string {
	switch {
	case selected:
		return fmt.Sprintf("✅%d", num)
	case !selectable:
		return fmt.Sprintf("🚫%d", num)
	default:
		return strconv.Itoa(num)
	}
}

func pageCount(items, size int) int {
	return max(1, (items+size-1)/size)
}

func truncate(s string, n int) string {
	r := []rune(s)
	if len(r) <= n {
		return s
	}
	return string(r[:n-1]) + "…"
}

// plain strips the HTML tags used in audio messages, for callback alerts.
func plain(s string) string {
	r := strings.NewReplacer("<b>", "", "</b>", "", "<i>", "", "</i>", "", "<code>", "", "</code>", "")
	return html.UnescapeString(r.Replace(s))
}
