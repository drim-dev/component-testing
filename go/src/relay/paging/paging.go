// Package paging implements the strict pagination contract (02-api.md §0.1): limit 1–100
// (default 50), VALIDATED never clamped. These exact 422s are the deterministic pin the
// §3 weakened-validation gaming story violates, so the bounds are identical across all
// five languages.
package paging

import (
	"strconv"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/apierr"
)

const (
	DefaultLimit = 50
	MinLimit     = 1
	MaxLimit     = 100
)

// Page is one page of items plus the cursor for the next page (nil when exhausted).
type Page[T any] struct {
	Items      []T     `json:"items"`
	NextBefore *string `json:"nextBefore"`
}

// ParseLimit validates the raw limit query param. Empty → default 50. Non-integer →
// 422 pagination:limit:not_a_number. Out of [1,100] → 422 pagination:limit:out_of_range.
func ParseLimit(raw string) (int, error) {
	if raw == "" {
		return DefaultLimit, nil
	}
	limit, err := strconv.Atoi(raw)
	if err != nil {
		return 0, apierr.Invalid("pagination:limit:not_a_number", "limit must be an integer.")
	}
	if limit < MinLimit || limit > MaxLimit {
		return 0, apierr.Invalid("pagination:limit:out_of_range", "limit must be between 1 and 100.")
	}
	return limit, nil
}

// ErrUnknownBefore is the 422 for a before cursor that was never returned in this scope.
func ErrUnknownBefore() error {
	return apierr.Invalid("pagination:before:unknown", "The before cursor is unknown.")
}

// Build assembles a Page from rows fetched with limit+1: if more than limit came back, the
// extra row signals there is a next page and its cursor is the last kept item's id.
func Build[T any](rows []T, limit int, id func(T) string) Page[T] {
	if len(rows) > limit {
		kept := rows[:limit]
		next := id(kept[len(kept)-1])
		return Page[T]{Items: kept, NextBefore: &next}
	}
	if rows == nil {
		rows = []T{}
	}
	return Page[T]{Items: rows, NextBefore: nil}
}
