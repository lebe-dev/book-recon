package telegram

import (
	"context"
	"fmt"
	"slices"
	"strings"
	"time"

	"github.com/charmbracelet/log"
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
	logger        *log.Logger
}

func New(token string, service *usecase.BookService, accessService *usecase.AccessService, userRepo domain.UserRepository, allowedUsers, adminUsers []string, version string, logger *log.Logger) (*Bot, error) {
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
	b.bot.Handle(telebot.OnText, b.handleSearch)

	b.bot.Handle(&telebot.Btn{Unique: "dl"}, b.handleDownload)
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
			return c.Send("⏳ Ваш запрос на доступ ожидает рассмотрения.")
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
			return c.Send("📨 Запрос на доступ отправлен администраторам. Ожидайте.")
		}

		return c.Send("⏳ Ваш запрос на доступ ожидает рассмотрения.")
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
		return c.Send(fmt.Sprintf("📚 *Book Recon* `%s`", b.version), telebot.ModeMarkdown)
	}

	name := sender.FirstName
	if name == "" {
		name = sender.Username
	}

	return c.Send(
		fmt.Sprintf("📚 *Book Recon*\n\nПривет, %s! Напишите название книги или имя автора — я найду и скачаю книгу.\n\nКоманды:\n/settings — настройки формата\n/help — справка", escapeMarkdown(name)),
		telebot.ModeMarkdown,
	)
}

func (b *Bot) handleHelp(c telebot.Context) error {
	text := "📖 *Справка*\n\n" +
		"Напишите название книги или имя автора — бот найдёт и предложит скачать.\n\n" +
		"*Советы:*\n" +
		"• Поиск идёт одновременно по нескольким источникам\n" +
		"• Результаты выводятся по 5, листайте кнопками\n" +
		"• На кнопках видно, какие форматы доступны\n\n" +
		"Форматы: EPUB, FB2\n\n" +
		"/settings — выбрать предпочитаемый формат"

	username := strings.ToLower(c.Sender().Username)
	if b.isAdmin(username) {
		text += "\n\n*Администрирование:*\n" +
			"/allowed\\_users — одобренные пользователи\n" +
			"/blocked\\_users — заблокированные пользователи"
	}

	return c.Send(text, telebot.ModeMarkdown)
}
