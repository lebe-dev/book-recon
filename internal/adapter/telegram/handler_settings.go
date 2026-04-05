package telegram

import (
	"github.com/lebe-dev/book-recon/internal/domain"
	"gopkg.in/telebot.v4"
)

func (b *Bot) handleSettings(c telebot.Context) error {
	b.logger.Debug("settings request", "username", c.Sender().Username)

	settings, err := b.service.GetSettings(c.Get("ctx").(contextKey).ctx, c.Sender().ID)
	if err != nil {
		return b.handleError(c, err)
	}

	return c.Send(b.msg.SettingsText(string(settings.PreferredFormat)), buildSettingsKeyboard(settings.PreferredFormat), telebot.ModeMarkdown)
}

func (b *Bot) handleSetFormat(c telebot.Context) error {
	formatStr := c.Data()
	b.logger.Debug("set format request", "username", c.Sender().Username, "format", formatStr)

	var format domain.Format
	switch domain.Format(formatStr) {
	case domain.FormatEPUB:
		format = domain.FormatEPUB
	case domain.FormatFB2:
		format = domain.FormatFB2
	case domain.FormatMOBI:
		format = domain.FormatMOBI
	case domain.FormatPDF:
		format = domain.FormatPDF
	case domain.FormatDJVU:
		format = domain.FormatDJVU
	default:
		b.logger.Warn("invalid format callback", "data", formatStr)
		return nil
	}

	if err := b.service.SetFormat(c.Get("ctx").(contextKey).ctx, c.Sender().ID, format); err != nil {
		return b.handleError(c, err)
	}

	return c.Edit(b.msg.SettingsText(string(format)), buildSettingsKeyboard(format), telebot.ModeMarkdown)
}
