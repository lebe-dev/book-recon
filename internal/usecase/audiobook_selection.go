package usecase

import (
	"maps"
	"path"
	"slices"
	"time"

	"github.com/lebe-dev/book-recon/internal/domain"
)

// AudiobookSelection is the file picker state for one torrent.
type AudiobookSelection struct {
	ID          string
	UserID      int64
	Title       string
	InfoHash    string
	Files       []domain.TorrentFile // offered files in display order
	Selected    map[int]bool         // keyed by TorrentFile.Index
	MaxFileSize int64                // files above it can't be selected; 0 = no limit
	Page        int
	FolderView  bool
	CreatedAt   time.Time

	torrent []byte
}

// AudiobookFolder groups offered files by directory.
type AudiobookFolder struct {
	Path  string // "" for the torrent root
	Files []int  // positions in AudiobookSelection.Files
}

// Selectable reports whether a file fits the per-file size limit.
func (s *AudiobookSelection) Selectable(f domain.TorrentFile) bool {
	return s.MaxFileSize <= 0 || f.Size <= s.MaxFileSize
}

func (s *AudiobookSelection) SelectedCount() int {
	return len(s.Selected)
}

func (s *AudiobookSelection) SelectedSize() int64 {
	var total int64
	for _, f := range s.Files {
		if s.Selected[f.Index] {
			total += f.Size
		}
	}
	return total
}

// SelectedIndexes returns torrent file indexes of selected files in display order.
func (s *AudiobookSelection) SelectedIndexes() []int {
	out := make([]int, 0, len(s.Selected))
	for _, f := range s.Files {
		if s.Selected[f.Index] {
			out = append(out, f.Index)
		}
	}
	return out
}

// TotalSize is the size of all offered files.
func (s *AudiobookSelection) TotalSize() int64 {
	var total int64
	for _, f := range s.Files {
		total += f.Size
	}
	return total
}

// ToggleFile flips selection of the file at display position pos.
func (s *AudiobookSelection) ToggleFile(pos int) {
	if pos < 0 || pos >= len(s.Files) {
		return
	}
	f := s.Files[pos]
	if s.Selected[f.Index] {
		delete(s.Selected, f.Index)
	} else if s.Selectable(f) {
		s.Selected[f.Index] = true
	}
}

// ToggleFolder selects all selectable files of a folder, or clears them if
// they are all selected already.
func (s *AudiobookSelection) ToggleFolder(i int) {
	folders := s.Folders()
	if i < 0 || i >= len(folders) {
		return
	}
	all := true
	for _, pos := range folders[i].Files {
		f := s.Files[pos]
		if s.Selectable(f) && !s.Selected[f.Index] {
			all = false
			break
		}
	}
	for _, pos := range folders[i].Files {
		f := s.Files[pos]
		if all {
			delete(s.Selected, f.Index)
		} else if s.Selectable(f) {
			s.Selected[f.Index] = true
		}
	}
}

// FolderState reports how many files of a folder are selected.
func (s *AudiobookSelection) FolderState(folder AudiobookFolder) (selected int, size int64) {
	for _, pos := range folder.Files {
		f := s.Files[pos]
		size += f.Size
		if s.Selected[f.Index] {
			selected++
		}
	}
	return selected, size
}

func (s *AudiobookSelection) SelectAll() {
	for _, f := range s.Files {
		if s.Selectable(f) {
			s.Selected[f.Index] = true
		}
	}
}

func (s *AudiobookSelection) SelectNone() {
	clear(s.Selected)
}

// Folders lists directories in display order.
func (s *AudiobookSelection) Folders() []AudiobookFolder {
	var out []AudiobookFolder
	idx := make(map[string]int)
	for pos, f := range s.Files {
		dir := path.Dir(f.Path)
		if dir == "." {
			dir = ""
		}
		i, ok := idx[dir]
		if !ok {
			i = len(out)
			idx[dir] = i
			out = append(out, AudiobookFolder{Path: dir})
		}
		out[i].Files = append(out[i].Files, pos)
	}
	return out
}

func (s *AudiobookSelection) clone() *AudiobookSelection {
	cp := *s
	cp.Files = slices.Clone(s.Files)
	cp.Selected = maps.Clone(s.Selected)
	cp.torrent = nil
	return &cp
}

// naturalCompare orders strings with embedded numbers numerically,
// so "Глава 2" goes before "Глава 10".
func naturalCompare(a, b string) int {
	for a != "" && b != "" {
		da, db := isDigit(a[0]), isDigit(b[0])
		switch {
		case da && db:
			na, ra := splitDigits(a)
			nb, rb := splitDigits(b)
			ta, tb := trimZeros(na), trimZeros(nb)
			if len(ta) != len(tb) {
				return cmpInt(len(ta), len(tb))
			}
			if ta != tb {
				if ta < tb {
					return -1
				}
				return 1
			}
			a, b = ra, rb
		case a[0] != b[0]:
			if a[0] < b[0] {
				return -1
			}
			return 1
		default:
			a, b = a[1:], b[1:]
		}
	}
	return cmpInt(len(a), len(b))
}

func isDigit(c byte) bool { return c >= '0' && c <= '9' }

func splitDigits(s string) (string, string) {
	i := 0
	for i < len(s) && isDigit(s[i]) {
		i++
	}
	return s[:i], s[i:]
}

func trimZeros(s string) string {
	for len(s) > 1 && s[0] == '0' {
		s = s[1:]
	}
	return s
}

func cmpInt(a, b int) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	}
	return 0
}
