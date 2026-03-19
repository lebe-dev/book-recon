package telegram

import (
	"context"
	"fmt"
	"slices"
	"strconv"
	"strings"

	"gopkg.in/telebot.v4"
)

func (b *Bot) handleApprove(c telebot.Context) error {
	return b.handleAccessDecision(c, true)
}

func (b *Bot) handleDeny(c telebot.Context) error {
	return b.handleAccessDecision(c, false)
}

func (b *Bot) handleAccessDecision(c telebot.Context, approved bool) error {
	ctx := c.Get("ctx").(contextKey).ctx
	caller := c.Sender()

	username := strings.ToLower(caller.Username)
	if !b.isAdmin(username) {
		return c.Respond(&telebot.CallbackResponse{Text: "Нет прав"})
	}

	data := c.Callback().Data
	telegramID, err := strconv.ParseInt(data, 10, 64)
	if err != nil {
		b.logger.Error("invalid callback data", "data", data, "error", err)
		return c.Respond(&telebot.CallbackResponse{Text: "Ошибка"})
	}

	if approved {
		err = b.accessService.ApproveUser(ctx, telegramID)
	} else {
		err = b.accessService.DenyUser(ctx, telegramID)
	}
	if err != nil {
		b.logger.Error("failed to update access status", "telegram_id", telegramID, "approved", approved, "error", err)
		return c.Respond(&telebot.CallbackResponse{Text: "Ошибка при обновлении"})
	}

	// Notify the user
	recipient := telebot.ChatID(telegramID)
	if approved {
		_, _ = b.bot.Send(recipient, "Ваш запрос на доступ одобрен. Напишите название книги для поиска.")
	} else {
		_, _ = b.bot.Send(recipient, "Ваш запрос на доступ отклонён.")
	}

	// Extract user name from original message for the log entry
	name := extractNameFromNotification(c.Message().Text)

	// Delete the message with buttons and send a clean log entry
	_ = b.bot.Delete(c.Message())

	if approved {
		_, _ = b.bot.Send(c.Chat(), fmt.Sprintf("✅ Доступ одобрен для %s", escapeMarkdown(name)), telebot.ModeMarkdown)
	} else {
		_, _ = b.bot.Send(c.Chat(), fmt.Sprintf("❌ Доступ отклонён для %s", escapeMarkdown(name)), telebot.ModeMarkdown)
	}

	return c.Respond()
}

func (b *Bot) notifyAdminsAboutRequest(ctx context.Context, sender *telebot.User) {
	adminIDs := b.accessService.ResolveAdminIDs(ctx, b.adminUsers)
	if len(adminIDs) == 0 {
		b.logger.Warn("no admin IDs resolved, cannot notify about access request", "admin_users", b.adminUsers)
		return
	}

	name := sender.FirstName
	if sender.LastName != "" {
		name += " " + sender.LastName
	}

	text := fmt.Sprintf("🔔 *Запрос на доступ*\n\nID: `%d`\nИмя: %s", sender.ID, escapeMarkdown(name))
	if sender.Username != "" {
		text += fmt.Sprintf("\nUsername: @%s", escapeMarkdown(sender.Username))
	}

	idStr := strconv.FormatInt(sender.ID, 10)
	markup := &telebot.ReplyMarkup{}
	markup.Inline(markup.Row(
		markup.Data("✅ Одобрить", "approve", idStr),
		markup.Data("❌ Отклонить", "deny", idStr),
	))

	for _, adminID := range adminIDs {
		recipient := telebot.ChatID(adminID)
		if _, err := b.bot.Send(recipient, text, markup, telebot.ModeMarkdown); err != nil {
			b.logger.Error("failed to notify admin", "admin_id", adminID, "error", err)
		}
	}
}

func (b *Bot) handleBlockedUsers(c telebot.Context) error {
	ctx := c.Get("ctx").(contextKey).ctx
	username := strings.ToLower(c.Sender().Username)

	if !b.isAdmin(username) {
		return nil
	}

	denied, err := b.accessService.ListDeniedUsers(ctx)
	if err != nil {
		b.logger.Error("failed to list denied users", "error", err)
		return c.Send("Ошибка при получении списка.")
	}

	if len(denied) == 0 {
		return c.Send("Заблокированных пользователей нет.")
	}

	for _, req := range denied {
		name := req.FirstName
		if name == "" {
			name = req.Username
		}
		if name == "" {
			name = fmt.Sprintf("ID %d", req.TelegramID)
		}

		text := fmt.Sprintf("🚫 %s", escapeMarkdown(name))
		if req.Username != "" {
			text += fmt.Sprintf(" (@%s)", escapeMarkdown(req.Username))
		}
		text += fmt.Sprintf("\nID: `%d`", req.TelegramID)

		idStr := strconv.FormatInt(req.TelegramID, 10)
		markup := &telebot.ReplyMarkup{}
		markup.Inline(markup.Row(
			markup.Data("Разблокировать", "unblock", idStr),
		))

		if err := c.Send(text, markup, telebot.ModeMarkdown); err != nil {
			b.logger.Error("failed to send blocked user entry", "error", err)
		}
	}
	return nil
}

func (b *Bot) handleUnblock(c telebot.Context) error {
	ctx := c.Get("ctx").(contextKey).ctx
	username := strings.ToLower(c.Sender().Username)

	if !b.isAdmin(username) {
		return c.Respond(&telebot.CallbackResponse{Text: "Нет прав"})
	}

	data := c.Callback().Data
	telegramID, err := strconv.ParseInt(data, 10, 64)
	if err != nil {
		return c.Respond(&telebot.CallbackResponse{Text: "Ошибка"})
	}

	if err := b.accessService.ApproveUser(ctx, telegramID); err != nil {
		b.logger.Error("failed to unblock user", "telegram_id", telegramID, "error", err)
		return c.Respond(&telebot.CallbackResponse{Text: "Ошибка при разблокировке"})
	}

	// Notify the unblocked user
	recipient := telebot.ChatID(telegramID)
	_, _ = b.bot.Send(recipient, "Ваш доступ восстановлен. Напишите название книги для поиска.")

	// Update the message — remove button
	originalText := c.Message().Text
	_, _ = b.bot.Edit(c.Message(), originalText+"\n\n✅ Разблокирован", telebot.ModeMarkdown)

	return c.Respond()
}

func (b *Bot) handleAllowedUsers(c telebot.Context) error {
	ctx := c.Get("ctx").(contextKey).ctx
	username := strings.ToLower(c.Sender().Username)

	if !b.isAdmin(username) {
		return nil
	}

	approved, err := b.accessService.ListApprovedUsers(ctx)
	if err != nil {
		b.logger.Error("failed to list approved users", "error", err)
		return c.Send("Ошибка при получении списка.")
	}

	if len(approved) == 0 {
		return c.Send("Одобренных пользователей нет.")
	}

	for _, req := range approved {
		name := req.FirstName
		if name == "" {
			name = req.Username
		}
		if name == "" {
			name = fmt.Sprintf("ID %d", req.TelegramID)
		}

		text := fmt.Sprintf("✅ %s", escapeMarkdown(name))
		if req.Username != "" {
			text += fmt.Sprintf(" (@%s)", escapeMarkdown(req.Username))
		}
		text += fmt.Sprintf("\nID: `%d`", req.TelegramID)

		idStr := strconv.FormatInt(req.TelegramID, 10)
		markup := &telebot.ReplyMarkup{}
		markup.Inline(markup.Row(
			markup.Data("Удалить доступ", "revoke", idStr),
		))

		if err := c.Send(text, markup, telebot.ModeMarkdown); err != nil {
			b.logger.Error("failed to send allowed user entry", "error", err)
		}
	}
	return nil
}

func (b *Bot) handleRevoke(c telebot.Context) error {
	ctx := c.Get("ctx").(contextKey).ctx
	username := strings.ToLower(c.Sender().Username)

	if !b.isAdmin(username) {
		return c.Respond(&telebot.CallbackResponse{Text: "Нет прав"})
	}

	data := c.Callback().Data
	telegramID, err := strconv.ParseInt(data, 10, 64)
	if err != nil {
		return c.Respond(&telebot.CallbackResponse{Text: "Ошибка"})
	}

	if err := b.accessService.RevokeUser(ctx, telegramID); err != nil {
		b.logger.Error("failed to revoke user", "telegram_id", telegramID, "error", err)
		return c.Respond(&telebot.CallbackResponse{Text: "Ошибка при удалении"})
	}

	originalText := c.Message().Text
	_, _ = b.bot.Edit(c.Message(), originalText+"\n\n🚫 Доступ удалён", telebot.ModeMarkdown)

	return c.Respond()
}

// extractNameFromNotification extracts the user name from the admin notification message.
func extractNameFromNotification(text string) string {
	for line := range strings.SplitSeq(text, "\n") {
		if name, ok := strings.CutPrefix(line, "Имя: "); ok {
			return name
		}
	}
	return "пользователь"
}

func (b *Bot) isAdmin(username string) bool {
	return slices.Contains(b.adminUsers, username)
}
