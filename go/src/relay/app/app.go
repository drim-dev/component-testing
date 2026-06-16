package app

import (
	"context"
	"encoding/json"
	"net/http"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/apierr"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/go-chi/chi/v5"
)

// App is the assembled Relay service: its Deps and the chi router built from them.
type App struct {
	d      Deps
	router chi.Router
}

// ServeHTTP makes App an http.Handler (so httptest can drive it directly — a real socket,
// the whole middleware + handler stack, no shortcuts).
func (a *App) ServeHTTP(w http.ResponseWriter, r *http.Request) { a.router.ServeHTTP(w, r) }

// New wires the router from d. The identity middleware runs first; handlers read the caller
// via currentUser.
func New(d Deps) *App {
	a := &App{d: d}
	r := chi.NewRouter()
	r.Use(a.recoverer)
	r.Use(a.identity)
	a.routes(r)
	a.router = r
	return a
}

type ctxKey int

const userKey ctxKey = 0

// identity resolves X-User-Id into a domain.User in the request context. Missing → 401
// identity:missing; unknown → 401 identity:unknown. POST /users is the only exempt route.
func (a *App) identity(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/users" && r.Method == http.MethodPost {
			next.ServeHTTP(w, r)
			return
		}
		id := r.Header.Get("X-User-Id")
		if id == "" {
			apierr.Write(w, apierr.Unauthorized("identity:missing", "X-User-Id header is required."))
			return
		}
		user, err := a.d.Store.UserByID(r.Context(), id)
		if err != nil {
			apierr.Write(w, err)
			return
		}
		if user == nil {
			apierr.Write(w, apierr.Unauthorized("identity:unknown", "X-User-Id does not match a known user."))
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), userKey, user)))
	})
}

func (a *App) recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				apierr.Write(w, apierr.NotFound("internal:error", "An unexpected error occurred."))
			}
		}()
		next.ServeHTTP(w, r)
	})
}

// currentUser returns the authenticated caller (nil only on the exempt POST /users path).
func currentUser(r *http.Request) *domain.User {
	u, _ := r.Context().Value(userKey).(*domain.User)
	return u
}

// ---- response helpers ----

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func decodeJSON(r *http.Request, dst any) error {
	if err := json.NewDecoder(r.Body).Decode(dst); err != nil {
		return apierr.Invalid("request:body:invalid", "Request body could not be read.")
	}
	return nil
}
