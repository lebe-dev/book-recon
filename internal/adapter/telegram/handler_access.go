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
		return c.Respond(&telebot.CallbackResponse{Text: b.msg.AccessNoPermission})
	}

	data := c.Callback().Data
	telegramID, err := strconv.ParseInt(data, 10, 64)
	if err != nil {
		b.logger.Error("invalid callback data", "data", data, "error", err)
		return c.Respond(&telebot.CallbackResponse{Text: b.msg.AccessError})
	}

	if approved {
		err = b.accessService.ApproveUser(ctx, telegramID)
	} else {
		err = b.accessService.DenyUser(ctx, telegramID)
	}
	if err != nil {
		b.logger.Error("failed to update access status", "telegram_id", telegramID, "approved", approved, "error", err)
		return c.Respond(&telebot.CallbackResponse{Text: b.msg.AccessUpdateError})
	}

	// Notify the user
	recipient := telebot.ChatID(telegramID)
	if approved {
		_, _ = b.bot.Send(recipient, b.msg.AccessApproved)
	} else {
		_, _ = b.bot.Send(recipient, b.msg.AccessDenied)
	}

	// Extract user name from original message for the log entry
	name := b.extractNameFromNotification(c.Message().Text)

	// Delete the message with buttons and send a clean log entry
	_ = b.bot.Delete(c.Message())

	if approved {
		_, _ = b.bot.Send(c.Chat(), b.msg.AccessApprovedFor(escapeMarkdown(name)), telebot.ModeMarkdown)
	} else {
		_, _ = b.bot.Send(c.Chat(), b.msg.AccessDeniedFor(escapeMarkdown(name)), telebot.ModeMarkdown)
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

	text := b.msg.AccessRequestNotify(sender.ID, escapeMarkdown(name), escapeMarkdown(sender.Username))

	idStr := strconv.FormatInt(sender.ID, 10)
	markup := &telebot.ReplyMarkup{}
	markup.Inline(markup.Row(
		markup.Data(b.msg.AccessBtnApprove, "approve", idStr),
		markup.Data(b.msg.AccessBtnDeny, "deny", idStr),
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
		return c.Send(b.msg.AccessListError)
	}

	if len(denied) == 0 {
		return c.Send(b.msg.AccessNoDenied)
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
			markup.Data(b.msg.AccessBtnUnblock, "unblock", idStr),
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
		return c.Respond(&telebot.CallbackResponse{Text: b.msg.AccessNoPermission})
	}

	data := c.Callback().Data
	telegramID, err := strconv.ParseInt(data, 10, 64)
	if err != nil {
		return c.Respond(&telebot.CallbackResponse{Text: b.msg.AccessError})
	}

	if err := b.accessService.ApproveUser(ctx, telegramID); err != nil {
		b.logger.Error("failed to unblock user", "telegram_id", telegramID, "error", err)
		return c.Respond(&telebot.CallbackResponse{Text: b.msg.AccessUnblockError})
	}

	// Notify the unblocked user
	recipient := telebot.ChatID(telegramID)
	_, _ = b.bot.Send(recipient, b.msg.AccessRestored)

	// Update the message — remove button
	originalText := c.Message().Text
	_, _ = b.bot.Edit(c.Message(), originalText+b.msg.AccessUnblocked, telebot.ModeMarkdown)

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
		return c.Send(b.msg.AccessListError)
	}

	if len(approved) == 0 {
		return c.Send(b.msg.AccessNoApproved)
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
			markup.Data(b.msg.AccessBtnRevoke, "revoke", idStr),
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
		return c.Respond(&telebot.CallbackResponse{Text: b.msg.AccessNoPermission})
	}

	data := c.Callback().Data
	telegramID, err := strconv.ParseInt(data, 10, 64)
	if err != nil {
		return c.Respond(&telebot.CallbackResponse{Text: b.msg.AccessError})
	}

	if err := b.accessService.RevokeUser(ctx, telegramID); err != nil {
		b.logger.Error("failed to revoke user", "telegram_id", telegramID, "error", err)
		return c.Respond(&telebot.CallbackResponse{Text: b.msg.AccessRevokeError})
	}

	name := b.extractNameFromAllowedUsers(c.Message().Text)

	_ = b.bot.Delete(c.Message())
	_, _ = b.bot.Send(c.Message().Chat, b.msg.AccessRevokedFor(name))

	return c.Respond()
}

// extractNameFromAllowedUsers extracts the user name from the /allowed_users list message.
// Message format: "✅ NAME (@username)\nID: `12345`"
func (b *Bot) extractNameFromAllowedUsers(text string) string {
	line := strings.SplitN(text, "\n", 2)[0]
	line = strings.TrimPrefix(line, "✅ ")
	if idx := strings.Index(line, " (@"); idx != -1 {
		line = line[:idx]
	}
	if line == "" {
		return b.msg.AccessFallbackName
	}
	return line
}

// extractNameFromNotification extracts the user name from the admin notification message.
func (b *Bot) extractNameFromNotification(text string) string {
	for line := range strings.SplitSeq(text, "\n") {
		if name, ok := strings.CutPrefix(line, b.msg.AccessRequestNamePrefix); ok {
			return name
		}
	}
	return b.msg.AccessFallbackName
}

func (b *Bot) isAdmin(username string) bool {
	return slices.Contains(b.adminUsers, username)
}
