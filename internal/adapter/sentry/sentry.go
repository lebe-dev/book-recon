// Package sentry wires the Sentry SDK into the application as a non-invasive
// log sink: error- and fatal-level log lines are forwarded as Sentry events and
// panics are reported via Recover. Only error reporting is enabled — performance
// tracing and profiling are intentionally off.
package sentry

import (
	"io"
	"time"

	"github.com/charmbracelet/log"
	sentrygo "github.com/getsentry/sentry-go"
)

const flushTimeout = 2 * time.Second

// Init initializes Sentry from configuration. An empty DSN disables Sentry: the
// returned flush is a no-op and enabled is false, so callers can wire the rest
// unconditionally. A non-empty but malformed DSN returns an error.
func Init(dsn, environment, release string, logger *log.Logger) (flush func(), enabled bool, err error) {
	if dsn == "" {
		logger.Debug("sentry disabled (no DSN configured)")
		return func() {}, false, nil
	}

	err = sentrygo.Init(sentrygo.ClientOptions{
		Dsn:              dsn,
		Environment:      environment,
		Release:          release,
		EnableTracing:    false,
		TracesSampleRate: 0,
	})
	if err != nil {
		return func() {}, false, err
	}

	logger.Info("sentry enabled", "environment", environment, "release", release)
	return func() { sentrygo.Flush(flushTimeout) }, true, nil
}

// WrapLogOutput returns an io.Writer that tees w unchanged while forwarding
// error- and fatal-level log lines to Sentry.
func WrapLogOutput(w io.Writer) io.Writer {
	return newLogWriter(w)
}

// Recover reports an in-flight panic to Sentry (best-effort), flushes, then
// re-panics to preserve the original crash behavior. Safe to defer even when
// Sentry is disabled. Use it to guard main and long-lived goroutines.
func Recover() {
	if r := recover(); r != nil {
		sentrygo.CurrentHub().Recover(r)
		sentrygo.Flush(flushTimeout)
		panic(r)
	}
}
