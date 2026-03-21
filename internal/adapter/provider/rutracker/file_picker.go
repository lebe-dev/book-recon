package rutracker

import (
	"path/filepath"
	"strings"

	"github.com/lebe-dev/book-recon/internal/domain"
)

const maxTelegramFileSize = 50 * 1024 * 1024 // 50 MB

// formatPriority defines the fallback order for format selection.
var formatPriority = []domain.Format{
	domain.FormatEPUB,
	domain.FormatFB2,
	domain.FormatMOBI,
	domain.FormatPDF,
	domain.FormatDJVU,
}

// bookExtensions maps file extensions to domain formats.
var bookExtensions = map[string]domain.Format{
	".epub": domain.FormatEPUB,
	".fb2":  domain.FormatFB2,
	".mobi": domain.FormatMOBI,
	".pdf":  domain.FormatPDF,
	".djvu": domain.FormatDJVU,
}

// PickedFile is a file selected from a torrent for sending to the user.
type PickedFile struct {
	Path   string
	Name   string
	Size   int64
	Format domain.Format
}

// PickFiles selects book files from downloaded torrent files.
// Priority: preferredFormat first, then fallback by formatPriority.
// Returns at most maxBooks files, each no larger than 50 MB.
func PickFiles(files []DownloadedFile, preferredFormat domain.Format, maxBooks int) []PickedFile {
	// Group files by format.
	grouped := make(map[domain.Format][]PickedFile)

	for _, f := range files {
		ext := strings.ToLower(filepath.Ext(f.Name))
		format, ok := bookExtensions[ext]
		if !ok {
			continue
		}
		if f.Size > maxTelegramFileSize {
			continue
		}
		grouped[format] = append(grouped[format], PickedFile{
			Path:   f.Path,
			Name:   f.Name,
			Size:   f.Size,
			Format: format,
		})
	}

	if len(grouped) == 0 {
		return nil
	}

	// Try preferred format first.
	if picks, ok := grouped[preferredFormat]; ok && len(picks) > 0 {
		return limitFiles(picks, maxBooks)
	}

	// Fallback by priority.
	for _, fmt := range formatPriority {
		if picks, ok := grouped[fmt]; ok && len(picks) > 0 {
			return limitFiles(picks, maxBooks)
		}
	}

	// Shouldn't reach here, but return first available.
	for _, picks := range grouped {
		return limitFiles(picks, maxBooks)
	}

	return nil
}

func limitFiles(files []PickedFile, max int) []PickedFile {
	if len(files) > max {
		return files[:max]
	}
	return files
}
