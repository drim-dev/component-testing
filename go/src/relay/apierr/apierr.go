// Package apierr is Relay's error model. Every deliberately-raised error carries an HTTP
// status and the pinned area:entity:reason code the acceptance catalog asserts. Handlers
// return these; the router translates them into the pinned JSON body (02-api.md §0).
//
// Existence-hiding (01-domain.md §5) is a property of the codes, not of special routing:
// an unknown-id 404 and an unauthorized 404 raise the SAME code+message, so their JSON
// bodies are byte-identical — exactly what the G-IDOR / G-BOLA-READ / G-S3 catches assert.
package apierr

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
)

// Error is a Relay API error: an HTTP status, a pinned code, and human text.
type Error struct {
	Status  int
	Code    string
	Message string
}

func (e *Error) Error() string { return fmt.Sprintf("%d %s: %s", e.Status, e.Code, e.Message) }

func new(status int, code, message string) *Error {
	return &Error{Status: status, Code: code, Message: message}
}

// Unauthorized — 401, the only identity failures (missing / unknown X-User-Id).
func Unauthorized(code, message string) *Error { return new(http.StatusUnauthorized, code, message) }

// Forbidden — 403, the caller can see the resource but lacks the membership/role.
func Forbidden(code, message string) *Error { return new(http.StatusForbidden, code, message) }

// NotFound — 404, resource absent OR existence hidden from an unauthorized caller.
func NotFound(code, message string) *Error { return new(http.StatusNotFound, code, message) }

// Conflict — 409, state conflict (duplicate handle, already a member, owner cannot leave).
func Conflict(code, message string) *Error { return new(http.StatusConflict, code, message) }

// TooLarge — 413, attachment over the size limit.
func TooLarge(code, message string) *Error {
	return new(http.StatusRequestEntityTooLarge, code, message)
}

// Invalid — 422, input failed validation or a business rule (no silent clamping).
func Invalid(code, message string) *Error { return new(http.StatusUnprocessableEntity, code, message) }

// Upstream — 502, an upstream (model / unfurl / presence stream) violated its contract.
func Upstream(code, message string) *Error { return new(http.StatusBadGateway, code, message) }

// Unavailable — 503, required infrastructure (the event broker) is unavailable.
func Unavailable(code, message string) *Error {
	return new(http.StatusServiceUnavailable, code, message)
}

type body struct {
	Status  int    `json:"status"`
	Code    string `json:"code"`
	Message string `json:"message"`
}

// Write renders err to w as the pinned JSON body. A non-*Error is rendered as a generic
// 500 so an unexpected panic recovery never leaks internals.
func Write(w http.ResponseWriter, err error) {
	var apiErr *Error
	if !errors.As(err, &apiErr) {
		apiErr = new(http.StatusInternalServerError, "internal:error", "An unexpected error occurred.")
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(apiErr.Status)
	_ = json.NewEncoder(w).Encode(body{Status: apiErr.Status, Code: apiErr.Code, Message: apiErr.Message})
}
