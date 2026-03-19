// Package encoding provides helpers for converting legacy 8-bit encodings
// (CP866, CP1251) to UTF-8. It is intended for use by book provider adapters
// that receive filenames from servers and ZIP archives that predate Unicode.
package encoding

import (
	"net/url"
	"strings"
	"unicode/utf8"

	"golang.org/x/text/encoding/charmap"
)

// ToUTF8 returns s unchanged if it is already valid UTF-8. Otherwise it tries
// each candidate encoding and returns the decode with the most Cyrillic
// characters (U+0400–U+04FF). Falls back to the original string if no
// candidate produces any Cyrillic.
func ToUTF8(s string, candidates ...*charmap.Charmap) string {
	if utf8.ValidString(s) {
		return s
	}

	best := s
	bestScore := 0

	for _, enc := range candidates {
		decoded, err := enc.NewDecoder().String(s)
		if err != nil {
			continue
		}
		if score := cyrillicCount(decoded); score > bestScore {
			bestScore = score
			best = decoded
		}
	}

	return best
}

// FilenameFromDisposition extracts the filename from a Content-Disposition
// header and returns it as valid UTF-8.
//
// Handles:
//   - filename*= (RFC 5987, always UTF-8)
//   - filename= with UTF-8 percent-encoding
//   - filename= with CP1251 percent-encoding (old Windows/IIS servers)
//   - filename= with CP866 percent-encoding
func FilenameFromDisposition(cd string) string {
	if cd == "" {
		return ""
	}

	// filename*= is always UTF-8 by spec — no encoding detection needed.
	if idx := strings.Index(cd, "filename*="); idx != -1 {
		val := cd[idx+len("filename*="):]
		if i := strings.IndexByte(val, ';'); i != -1 {
			val = val[:i]
		}
		val = strings.TrimSpace(val)
		if parts := strings.SplitN(val, "''", 2); len(parts) == 2 {
			if decoded, err := url.PathUnescape(parts[1]); err == nil {
				return decoded
			}
		}
	}

	if idx := strings.Index(cd, "filename="); idx != -1 {
		val := cd[idx+len("filename="):]
		if i := strings.IndexByte(val, ';'); i != -1 {
			val = val[:i]
		}
		val = strings.TrimSpace(val)
		val = strings.Trim(val, `"`)
		if decoded, err := url.PathUnescape(val); err == nil {
			// Percent-decoded bytes may be raw UTF-8 or a legacy 8-bit encoding.
			return ToUTF8(decoded, charmap.Windows1251, charmap.CodePage866)
		}
		return val
	}

	return ""
}

// DecodeZipFilename ensures a ZIP entry name is valid UTF-8.
// ZIP archives without the UTF-8 flag store names in the local OEM code page —
// CP866 for Russian Windows/DOS tools.
func DecodeZipFilename(s string) string {
	return ToUTF8(s, charmap.CodePage866, charmap.Windows1251)
}

// cyrillicCount counts Cyrillic characters (U+0400–U+04FF) in s.
func cyrillicCount(s string) int {
	n := 0
	for _, r := range s {
		if r >= 0x0400 && r <= 0x04FF {
			n++
		}
	}
	return n
}
