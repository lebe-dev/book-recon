// Package tempbuf buffers a download stream on disk instead of in memory.
//
// Book files reach tens of megabytes, and a bot container is usually capped at
// a few hundred. Holding a whole book in a byte slice — plus the copies made
// while the slice grows — is enough to get the process killed by the OOM
// killer, which leaves no panic and no error report behind.
package tempbuf

import (
	"io"
	"os"
)

// MaxDownloadSize caps how much of a stream is written to disk. It leaves
// headroom above the 50 MB Telegram upload limit so the caller can still tell
// "too large" from "truncated".
const MaxDownloadSize = 64 * 1024 * 1024

// File is a stream buffered in a temporary file. Close removes the file.
type File struct {
	file   *os.File
	size   int64
	closed bool
}

// Buffer copies r into a temporary file, up to MaxDownloadSize.
func Buffer(r io.Reader) (*File, error) {
	return BufferLimit(r, MaxDownloadSize)
}

// BufferLimit copies at most limit+1 bytes of r into a temporary file. The
// extra byte lets the caller detect that the limit was exceeded.
func BufferLimit(r io.Reader, limit int64) (*File, error) {
	f, err := os.CreateTemp("", "book-recon-dl-*")
	if err != nil {
		return nil, err
	}

	size, err := io.Copy(f, io.LimitReader(r, limit+1))
	if err != nil {
		_ = f.Close()
		_ = os.Remove(f.Name())
		return nil, err
	}

	return &File{file: f, size: size}, nil
}

// Size returns the number of bytes buffered.
func (f *File) Size() int64 { return f.size }

// ReaderAt exposes the buffer for random access, e.g. to archive/zip.
func (f *File) ReaderAt() io.ReaderAt { return f.file }

// Rewound returns the whole buffer from its start. Closing the returned reader
// removes the temporary file.
func (f *File) Rewound() (io.ReadCloser, error) {
	if _, err := f.file.Seek(0, io.SeekStart); err != nil {
		return nil, err
	}
	return &reader{Reader: f.file, buf: f}, nil
}

// Wrap returns rc extended with the buffer's cleanup, for readers that read
// through the buffer (a zip entry, for one).
func (f *File) Wrap(rc io.ReadCloser) io.ReadCloser {
	return &reader{Reader: rc, inner: rc, buf: f}
}

// Close closes the temporary file and removes it. Safe to call more than once.
func (f *File) Close() error {
	if f.closed {
		return nil
	}
	f.closed = true

	err := f.file.Close()
	if rmErr := os.Remove(f.file.Name()); err == nil && !os.IsNotExist(rmErr) {
		err = rmErr
	}
	return err
}

// reader ties a stream's lifetime to the buffer backing it.
type reader struct {
	io.Reader
	inner io.Closer
	buf   *File
}

func (r *reader) Close() error {
	var err error
	if r.inner != nil {
		err = r.inner.Close()
	}
	if bufErr := r.buf.Close(); err == nil {
		err = bufErr
	}
	return err
}
