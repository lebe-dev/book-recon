package telegram

import (
	"context"
	"errors"
	"strings"

	"github.com/lebe-dev/book-recon/internal/domain"
	"github.com/lebe-dev/book-recon/internal/usecase"
	"gopkg.in/telebot.v4"
)

// SetAppClientService enables linking Android app clients through /start link_<code>.
func (b *Bot) SetAppClientService(svc *usecase.AppClientService) {
	b.appClients = svc
}

// Username is the bot's Telegram username, used to build t.me links.
func (b *Bot) Username() string {
	return b.bot.Me.Username
}

// linkCodeFromStart returns the link code of a "/start link_<code>" message.
func linkCodeFromStart(text string) (string, bool) {
	fields := strings.Fields(text)
	if len(fields) != 2 || fields[0] != "/start" {
		return "", false
	}
	if !strings.HasPrefix(fields[1], usecase.LinkCodePrefix) {
		return "", false
	}
	return fields[1], true
}

// appLinkMiddleware handles app link codes before the access check: a stranger who links
// the app must get an app-specific request, not the plain bot one.
func (b *Bot) appLinkMiddleware(next telebot.HandlerFunc) telebot.HandlerFunc {
	return func(c telebot.Context) error {
		if b.appClients == nil || c.Message() == nil {
			return next(c)
		}
		code, ok := linkCodeFromStart(c.Text())
		if !ok {
			return next(c)
		}
		return b.handleAppLink(c, code)
	}
}

func (b *Bot) handleAppLink(c telebot.Context, code string) error {
	ctx := context.Background()
	sender := c.Sender()

	if err := b.userRepo.Register(ctx, sender.ID, sender.Username); err != nil {
		b.logger.Error("failed to register user", "id", sender.ID, "username", sender.Username, "error", err)
	}

	result, err := b.appClients.LinkTelegram(ctx, code, usecase.TelegramUser{
		ID:        sender.ID,
		Username:  sender.Username,
		FirstName: sender.FirstName,
	})
	if errors.Is(err, usecase.ErrLinkCodeInvalid) {
		return c.Send(b.msg.AppLinkInvalid)
	}
	if err != nil {
		b.logger.Error("failed to link app client", "telegram_id", sender.ID, "error", err)
		return c.Send(b.msg.AccessError)
	}

	switch result.Status {
	case domain.AppClientApproved:
		return c.Send(b.msg.AppLinkApproved)
	case domain.AppClientDenied:
		// Same as the bot itself: a denied user gets no answer and admins are not bothered.
		b.logger.Debug("app link from a denied user", "telegram_id", sender.ID)
		return nil
	}

	if !result.NewRequest {
		return c.Send(b.msg.AccessPending)
	}
	b.logger.Info("new access request from the android app", "telegram_id", sender.ID, "username", sender.Username)
	go b.notifyAdminsAboutRequest(ctx, sender, b.msg.AccessRequestFromApp(escapeMarkdown(result.Client.DeviceName)))
	return c.Send(b.msg.AppLinkRequestSent)
}

// appClientsSuffix is the device count line for the user lists, empty without devices.
func (b *Bot) appClientsSuffix(counts map[int64]int, telegramID int64) string {
	if n := counts[telegramID]; n > 0 {
		return b.msg.AppClientsCount(n)
	}
	return ""
}

// linkedAppClients returns device counts per user; errors only cost the extra line.
func (b *Bot) linkedAppClients(ctx context.Context) map[int64]int {
	if b.appClients == nil {
		return nil
	}
	counts, err := b.appClients.LinkedCounts(ctx)
	if err != nil {
		b.logger.Warn("failed to count app clients", "error", err)
		return nil
	}
	return counts
}
