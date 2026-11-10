// Package text tidies text that clients send.
package text

import (
	"strings"
	"unicode/utf8"
)

// Clip trims s and shortens it to at most n characters.
func Clip(s string, n int) string {
	s = strings.TrimSpace(s)
	if utf8.RuneCountInString(s) <= n {
		return s
	}
	return string([]rune(s)[:n])
}
