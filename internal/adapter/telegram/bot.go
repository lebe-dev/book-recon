package telegram

import (
	"context"
	"slices"
	"strings"
	"time"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/usecase"
	"gopkg.in/telebot.v4"
)

const pageSize = 5

type contextKey struct {
	ctx context.Context
}

type Bot struct {
	bot          *telebot.Bot
	service      *usecase.BookService
	allowedUsers []string
	adminUsers   []string
	logger       *log.Logger
}

func New(token string, service *usecase.BookService, allowedUsers, adminUsers []string, logger *log.Logger) (*Bot, error) {
	pref := telebot.Settings{
		Token:  token,
		Poller: &telebot.LongPoller{Timeout: 10 * time.Second},
	}

	bot, err := telebot.NewBot(pref)
	if err != nil {
		return nil, err
	}

	b := &Bot{
		bot:          bot,
		service:      service,
		allowedUsers: allowedUsers,
		adminUsers:   adminUsers,
		logger:       logger,
	}

	b.setupRoutes()
	return b, nil
}

func (b *Bot) Start() {
	b.bot.Start()
}

func (b *Bot) Stop() {
	b.bot.Stop()
}

func (b *Bot) setupRoutes() {
	b.bot.Use(b.accessMiddleware)
	b.bot.Use(b.contextMiddleware)

	b.bot.Handle("/start", b.handleStart)
	b.bot.Handle("/help", b.handleHelp)
	b.bot.Handle("/settings", b.handleSettings)
	b.bot.Handle(telebot.OnText, b.handleSearch)

	b.bot.Handle(&telebot.Btn{Unique: "dl"}, b.handleDownload)
	b.bot.Handle(&telebot.Btn{Unique: "page"}, b.handlePage)
	b.bot.Handle(&telebot.Btn{Unique: "fmt"}, b.handleSetFormat)
}

func (b *Bot) accessMiddleware(next telebot.HandlerFunc) telebot.HandlerFunc {
	return func(c telebot.Context) error {
		if len(b.allowedUsers) == 0 {
			b.logger.Warn("access denied: no allowed users configured", "username", c.Sender().Username)
			return nil
		}

		username := strings.ToLower(c.Sender().Username)
		if !slices.Contains(b.allowedUsers, username) {
			b.logger.Warn("access denied", "username", username)
			return nil
		}

		return next(c)
	}
}

func (b *Bot) contextMiddleware(next telebot.HandlerFunc) telebot.HandlerFunc {
	return func(c telebot.Context) error {
		c.Set("ctx", contextKey{ctx: context.Background()})
		return next(c)
	}
}

func (b *Bot) handleStart(c telebot.Context) error {
	return c.Send(
		"📚 *Book Recon*\n\n"+
			"Отправьте название книги или имя автора — я найду и скачаю книгу.\n\n"+
			"Команды:\n"+
			"/settings — настройки формата\n"+
			"/help — справка",
		telebot.ModeMarkdown,
	)
}

func (b *Bot) handleHelp(c telebot.Context) error {
	return c.Send(
		"📖 *Справка*\n\n"+
			"Просто напишите название книги или имя автора.\n"+
			"Я найду книги и предложу скачать в нужном формате.\n\n"+
			"Поддерживаемые форматы: EPUB, FB2\n\n"+
			"/settings — выбрать предпочитаемый формат",
		telebot.ModeMarkdown,
	)
}
