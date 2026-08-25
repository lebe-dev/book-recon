package telegram

import (
	"fmt"
	"runtime/debug"

	"gopkg.in/telebot.v4"
)

// recoverMiddleware turns a panic inside a handler into an ordinary error.
// Telebot runs every handler in its own goroutine, so an unrecovered panic
// there kills the process — the user sees the bot restart and nothing explains
// why. The panic is logged at error level, which is what feeds Sentry.
func (b *Bot) recoverMiddleware(next telebot.HandlerFunc) telebot.HandlerFunc {
	return func(c telebot.Context) (err error) {
		defer func() {
			r := recover()
			if r == nil {
				return
			}

			b.logger.Error("handler panicked", "panic", r, "stack", string(debug.Stack()))

			if c != nil {
				if sendErr := c.Send(b.msg.ErrUnexpected); sendErr != nil {
					b.logger.Warn("failed to report panic to user", "error", sendErr)
				}
			}

			err = fmt.Errorf("handler panicked: %v", r)
		}()

		return next(c)
	}
}
