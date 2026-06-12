package sentry

import (
	"bytes"
	"io"
	"regexp"
	"strings"
	"time"

	sentrygo "github.com/getsentry/sentry-go"
)

// ansiRe matches SGR color escape sequences emitted when logging to a TTY.
var ansiRe = regexp.MustCompile("\x1b\\[[0-9;]*m")

// levelMaxField is how far into a log line the level token may appear. With
// timestamps the layout is "<date> <time> <LEVEL> <msg>", so the level sits at
// field index 2. Bounding the search avoids treating a stray "ERROR" word in an
// info-level message body as the line's level.
const levelMaxField = 2

// logWriter tees every byte to under unchanged and additionally forwards
// error- and fatal-level log lines to Sentry. Forwarding is best-effort and
// never alters the result of the underlying write.
type logWriter struct {
	under   io.Writer
	capture func(level sentrygo.Level, title, full string)
	flush   func(timeout time.Duration) bool
}

func newLogWriter(under io.Writer) *logWriter {
	return &logWriter{
		under: under,
		capture: func(level sentrygo.Level, title, full string) {
			sentrygo.WithScope(func(scope *sentrygo.Scope) {
				scope.SetLevel(level)
				scope.SetContext("log", sentrygo.Context{"line": full})
				sentrygo.CaptureMessage(title)
			})
		},
		flush: sentrygo.Flush,
	}
}

func (w *logWriter) Write(p []byte) (int, error) {
	n, err := w.under.Write(p)

	for line := range bytes.SplitSeq(p, []byte{'\n'}) {
		if len(line) == 0 {
			continue
		}
		level, title, ok := parseLine(string(line))
		if !ok {
			continue
		}
		w.capture(level, title, stripANSI(string(line)))
		if level == sentrygo.LevelFatal {
			// logger.Fatal calls os.Exit immediately after this write returns,
			// so flush synchronously to avoid dropping the event.
			w.flush(2 * time.Second)
		}
	}

	return n, err
}

// parseLine extracts the severity and a stable title (the static message,
// without the varying key=value pairs) from a charmbracelet/log text line.
func parseLine(line string) (sentrygo.Level, string, bool) {
	fields := strings.Fields(stripANSI(line))

	levelIdx := -1
	var level sentrygo.Level
	for i, f := range fields {
		if i > levelMaxField {
			break
		}
		switch f {
		case "ERRO", "ERROR":
			level, levelIdx = sentrygo.LevelError, i
		case "FATA", "FATAL":
			level, levelIdx = sentrygo.LevelFatal, i
		}
		if levelIdx >= 0 {
			break
		}
	}
	if levelIdx < 0 {
		return "", "", false
	}

	rest := fields[levelIdx+1:]
	msg := make([]string, 0, len(rest))
	for _, tok := range rest {
		if strings.Contains(tok, "=") {
			break // first key=value token marks the end of the message
		}
		msg = append(msg, tok)
	}

	title := strings.Join(msg, " ")
	if title == "" {
		title = strings.Join(rest, " ")
	}
	return level, title, true
}

func stripANSI(s string) string {
	return ansiRe.ReplaceAllString(s, "")
}
