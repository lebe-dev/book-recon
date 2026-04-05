package telegram

import (
	"context"
	"fmt"
	"strings"
	"time"

	"gopkg.in/telebot.v4"
)

func (b *Bot) handleHealth(c telebot.Context) error {
	username := strings.ToLower(c.Sender().Username)
	if !b.isAdmin(username) {
		return nil
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	statuses := b.service.CheckHealth(ctx)
	if len(statuses) == 0 {
		return c.Send(b.msg.HealthNoProviders, telebot.ModeMarkdown)
	}

	var sb strings.Builder
	sb.WriteString(b.msg.HealthTitle)

	for _, s := range statuses {
		icon := "✅"
		if !s.Healthy {
			icon = "❌"
		}
		fmt.Fprintf(&sb, "%s *%s* — `%s`\n", icon, escapeMarkdown(s.Name), escapeMarkdown(s.Detail))
	}

	return c.Send(sb.String(), telebot.ModeMarkdown)
}
