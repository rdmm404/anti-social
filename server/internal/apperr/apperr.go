// Package apperr is the one error type the domains return for anything a client did or asked
// for. Its message is safe to show. Any other error is a failure on the server's side.
package apperr

import (
	"errors"
	"fmt"
)

type Kind string

const (
	// The request doesn't make sense: a malformed id, a value out of bounds.
	Invalid Kind = "invalid"
	// The rules say no: no time left, no session open.
	Refused      Kind = "refused"
	NotFound     Kind = "not_found"
	Unauthorized Kind = "unauthorized"
)

type Error struct {
	Kind    Kind
	Message string
}

func (e *Error) Error() string { return e.Message }

func New(kind Kind, format string, args ...any) error {
	return &Error{Kind: kind, Message: fmt.Sprintf(format, args...)}
}

// As returns err as an *Error, if it is or wraps one.
func As(err error) (*Error, bool) {
	var e *Error
	ok := errors.As(err, &e)
	return e, ok
}

// Is reports whether err is an *Error of this kind.
func Is(err error, kind Kind) bool {
	e, ok := As(err)
	return ok && e.Kind == kind
}
