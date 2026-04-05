package telegram

import (
	"strings"

	"gopkg.in/telebot.v4"
)

func (b *Bot) handleWhatsNew(c telebot.Context) error {
	username := strings.ToLower(c.Sender().Username)
	if !b.isAdmin(username) {
		return nil
	}

	text := extractBroadcastText(c.Message().Text)
	if text == "" {
		return c.Send(b.msg.BroadcastUsage, telebot.ModeMarkdown)
	}

	ctx := c.Get("ctx").(contextKey).ctx

	ids, err := b.userRepo.ListAllIDs(ctx)
	if err != nil {
		b.logger.Error("failed to list user IDs for broadcast", "error", err)
		return c.Send(b.msg.BroadcastUserListErr)
	}

	message := b.msg.BroadcastTitle(text)

	var sent, failed int
	for _, id := range ids {
		recipient := telebot.ChatID(id)
		if _, err := b.bot.Send(recipient, message, telebot.ModeMarkdown); err != nil {
			b.logger.Warn("failed to send broadcast", "telegram_id", id, "error", err)
			failed++
		} else {
			sent++
		}
	}

	return c.Send(b.msg.BroadcastComplete(sent, failed))
}

// extractBroadcastText extracts the message body from a /whats_new command.
// Supports both "/whats_new text" and multi-line messages where text follows on the next line.
func extractBroadcastText(raw string) string {
	// Remove the command itself
	after, found := strings.CutPrefix(raw, "/whats_new")
	if !found {
		return ""
	}
	return strings.TrimSpace(after)
}
