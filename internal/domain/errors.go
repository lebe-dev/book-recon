package domain

import "fmt"

type ErrorCode string

const (
	ErrCodeNotFound        ErrorCode = "NOT_FOUND"
	ErrCodeFormatNA        ErrorCode = "FORMAT_NOT_AVAILABLE"
	ErrCodeProviderError   ErrorCode = "PROVIDER_ERROR"
	ErrCodeFileTooLarge    ErrorCode = "FILE_TOO_LARGE"
	ErrCodeTimeout         ErrorCode = "TIMEOUT"
	ErrCodeValidation      ErrorCode = "VALIDATION_ERROR"
	ErrCodeBookUnavailable ErrorCode = "BOOK_UNAVAILABLE"
)

// DomainError is the base error type for all domain-level errors.
type DomainError struct {
	Code    ErrorCode
	Message string
	Err     error
}

func (e *DomainError) Error() string {
	if e.Err != nil {
		return fmt.Sprintf("%s: %s: %v", e.Code, e.Message, e.Err)
	}
	return fmt.Sprintf("%s: %s", e.Code, e.Message)
}

func (e *DomainError) Unwrap() error {
	return e.Err
}

func NewError(code ErrorCode, message string) *DomainError {
	return &DomainError{Code: code, Message: message}
}

func WrapError(code ErrorCode, message string, err error) *DomainError {
	return &DomainError{Code: code, Message: message, Err: err}
}

// ErrorCodeFrom extracts ErrorCode from an error if it is a DomainError.
func ErrorCodeFrom(err error) (ErrorCode, bool) {
	if de, ok := err.(*DomainError); ok {
		return de.Code, true
	}
	return "", false
}
