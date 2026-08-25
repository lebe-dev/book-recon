package telegram

import (
	"bytes"
	"strings"
	"testing"

	"github.com/charmbracelet/log"
	"github.com/lebe-dev/book-recon/internal/adapter/i18n"
	"gopkg.in/telebot.v4"
)

// TestRecoverMiddleware_TurnsPanicIntoError makes sure a panicking handler does
// not take the whole process down: telebot runs handlers in their own
// goroutine, where an unrecovered panic is fatal.
func TestRecoverMiddleware_TurnsPanicIntoError(t *testing.T) {
	var out bytes.Buffer
	logger := log.New(&out)
	logger.SetLevel(log.ErrorLevel)

	b := &Bot{msg: mustMessages(t), logger: logger}

	handler := b.recoverMiddleware(func(c telebot.Context) error {
		panic("boom")
	})

	err := handler(nil)
	if err == nil {
		t.Fatal("expected an error for a recovered panic")
	}
	if !strings.Contains(err.Error(), "boom") {
		t.Fatalf("error = %v, want it to mention the panic value", err)
	}
	if !strings.Contains(out.String(), "handler panicked") {
		t.Fatalf("log = %q, want a panic record", out.String())
	}
}

func mustMessages(t *testing.T) *i18n.Messages {
	t.Helper()
	msg, err := i18n.Load("en")
	if err != nil {
		t.Fatalf("load messages: %v", err)
	}
	return msg
}

func TestRecoverMiddleware_PassesResultThrough(t *testing.T) {
	b := &Bot{msg: mustMessages(t), logger: log.New(&bytes.Buffer{})}

	called := false
	handler := b.recoverMiddleware(func(c telebot.Context) error {
		called = true
		return nil
	})

	if err := handler(nil); err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if !called {
		t.Fatal("handler was not called")
	}
}
