package telegram

import (
	"context"
	"slices"
	"strings"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/adapter/i18n"
	"github.com/lebe-dev/book-recon/internal/domain"
	"github.com/lebe-dev/book-recon/internal/usecase"
	"gopkg.in/telebot.v4"
)

const pageSize = 5

type contextKey struct {
	ctx context.Context
}

type Bot struct {
	bot           *telebot.Bot
	service       *usecase.BookService
	accessService *usecase.AccessService
	userRepo      domain.UserRepository
	allowedUsers  []string
	adminUsers    []string
	version       string
	msg           *i18n.Messages
	logger        *log.Logger
}

func New(token string, service *usecase.BookService, accessService *usecase.AccessService, userRepo domain.UserRepository, allowedUsers, adminUsers []string, version string, msg *i18n.Messages, logger *log.Logger) (*Bot, error) {
	pref := telebot.Settings{
		Token:  token,
		Poller: &telebot.LongPoller{Timeout: 10 * time.Second},
	}

	bot, err := telebot.NewBot(pref)
	if err != nil {
		return nil, err
	}

	b := &Bot{
		bot:           bot,
		service:       service,
		accessService: accessService,
		userRepo:      userRepo,
		allowedUsers:  allowedUsers,
		adminUsers:    adminUsers,
		version:       version,
		msg:           msg,
		logger:        logger,
	}

	b.setupRoutes()
	return b, nil
}

func (b *Bot) Start() {
	b.logger.Info("telegram bot starting")
	b.bot.Start()
}

func (b *Bot) Stop() {
	b.logger.Info("telegram bot stopping")
	b.bot.Stop()
}

func (b *Bot) setupRoutes() {
	b.bot.Use(b.accessMiddleware)
	b.bot.Use(b.contextMiddleware)

	b.bot.Handle("/start", b.handleStart)
	b.bot.Handle("/help", b.handleHelp)
	b.bot.Handle("/settings", b.handleSettings)
	b.bot.Handle("/blocked_users", b.handleBlockedUsers)
	b.bot.Handle("/allowed_users", b.handleAllowedUsers)
	b.bot.Handle("/whats_new", b.handleWhatsNew)
	b.bot.Handle("/health", b.handleHealth)
	b.bot.Handle(telebot.OnText, b.handleSearch)

	b.bot.Handle(&telebot.Btn{Unique: "dl"}, b.handleDownload)
	b.bot.Handle(&telebot.Btn{Unique: "dlf"}, b.handleDownloadFormat)
	b.bot.Handle(&telebot.Btn{Unique: "page"}, b.handlePage)
	b.bot.Handle(&telebot.Btn{Unique: "fmt"}, b.handleSetFormat)
	b.bot.Handle(&telebot.Btn{Unique: "approve"}, b.handleApprove)
	b.bot.Handle(&telebot.Btn{Unique: "deny"}, b.handleDeny)
	b.bot.Handle(&telebot.Btn{Unique: "unblock"}, b.handleUnblock)
	b.bot.Handle(&telebot.Btn{Unique: "revoke"}, b.handleRevoke)
	b.bot.Handle(&telebot.Btn{Unique: "noop"}, func(c telebot.Context) error { return c.Respond() })
}

func (b *Bot) accessMiddleware(next telebot.HandlerFunc) telebot.HandlerFunc {
	return func(c telebot.Context) error {
		sender := c.Sender()
		username := strings.ToLower(sender.Username)

		// Static allowlist
		if slices.Contains(b.allowedUsers, username) {
			b.logger.Debug("access granted via allowlist", "username", username)
			return next(c)
		}

		// Admin list
		if b.isAdmin(username) {
			b.logger.Debug("access granted via admin list", "username", username)
			return next(c)
		}

		// Check DB status
		ctx := context.Background()
		status, err := b.accessService.CheckAccess(ctx, sender.ID)
		if err != nil {
			b.logger.Error("failed to check access", "telegram_id", sender.ID, "error", err)
			return nil
		}

		switch status {
		case domain.AccessStatusApproved:
			b.logger.Debug("access granted via approval", "telegram_id", sender.ID)
			return next(c)
		case domain.AccessStatusPending:
			return c.Send(b.msg.AccessPending)
		case domain.AccessStatusDenied:
			b.logger.Debug("access denied (denied status)", "telegram_id", sender.ID)
			return nil
		}

		// No record — create pending request and notify admins
		created, err := b.accessService.RequestAccess(ctx, domain.AccessRequest{
			TelegramID: sender.ID,
			Username:   sender.Username,
			FirstName:  sender.FirstName,
		})
		if err != nil {
			b.logger.Error("failed to create access request", "telegram_id", sender.ID, "error", err)
			return nil
		}

		if created {
			b.logger.Info("new access request", "telegram_id", sender.ID, "username", sender.Username)
			go b.notifyAdminsAboutRequest(ctx, sender)
			return c.Send(b.msg.AccessRequestSent)
		}

		return c.Send(b.msg.AccessPending)
	}
}

func (b *Bot) contextMiddleware(next telebot.HandlerFunc) telebot.HandlerFunc {
	return func(c telebot.Context) error {
		c.Set("ctx", contextKey{ctx: context.Background()})
		return next(c)
	}
}

func (b *Bot) handleStart(c telebot.Context) error {
	ctx := c.Get("ctx").(contextKey).ctx
	sender := c.Sender()

	if err := b.userRepo.Register(ctx, sender.ID, sender.Username); err != nil {
		b.logger.Error("failed to register user", "id", sender.ID, "username", sender.Username, "error", err)
	}

	username := strings.ToLower(sender.Username)
	if slices.Contains(b.adminUsers, username) {
		return c.Send(b.msg.StartAdmin(b.version), telebot.ModeMarkdown)
	}

	name := sender.FirstName
	if name == "" {
		name = sender.Username
	}

	return c.Send(b.msg.StartUser(escapeMarkdown(name)), telebot.ModeMarkdown)
}

func (b *Bot) handleHelp(c telebot.Context) error {
	username := strings.ToLower(c.Sender().Username)
	return c.Send(b.msg.HelpText(b.isAdmin(username)), telebot.ModeMarkdown)
}

// NotifyProviderError sends a provider error alert to all admin users.
func (b *Bot) NotifyProviderError(providerName string, err error) {
	ctx := context.Background()
	adminIDs := b.accessService.ResolveAdminIDs(ctx, b.adminUsers)
	if len(adminIDs) == 0 {
		return
	}

	text := b.msg.ProviderError(escapeMarkdown(providerName), escapeMarkdown(err.Error()))

	for _, adminID := range adminIDs {
		recipient := telebot.ChatID(adminID)
		if _, sendErr := b.bot.Send(recipient, text, telebot.ModeMarkdown); sendErr != nil {
			b.logger.Error("failed to notify admin about provider error", "admin_id", adminID, "error", sendErr)
		}
	}
}
