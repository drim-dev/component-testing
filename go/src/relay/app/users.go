package app

import (
	"net/http"
	"regexp"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/apierr"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/store"
	"github.com/go-chi/chi/v5"
)

var handlePattern = regexp.MustCompile(`^[a-z0-9_]+$`)

type userDto struct {
	ID          string    `json:"id"`
	Handle      string    `json:"handle"`
	DisplayName string    `json:"displayName"`
	CreatedAt   time.Time `json:"createdAt"`
}

func toUserDto(u domain.User) userDto {
	return userDto{ID: u.ID, Handle: u.Handle, DisplayName: u.DisplayName, CreatedAt: u.CreatedAt}
}

func (a *App) createUser(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Handle      string `json:"handle"`
		DisplayName string `json:"displayName"`
	}
	if err := decodeJSON(r, &body); err != nil {
		apierr.Write(w, err)
		return
	}
	if len(body.Handle) < 3 || len(body.Handle) > 32 || !handlePattern.MatchString(body.Handle) {
		apierr.Write(w, apierr.Invalid("user:handle:invalid", "handle must be 3–32 chars of [a-z0-9_]."))
		return
	}
	if len(body.DisplayName) < 1 || len(body.DisplayName) > 64 {
		apierr.Write(w, apierr.Invalid("user:display_name:invalid", "displayName must be 1–64 chars."))
		return
	}

	user := domain.User{ID: a.d.IDs.Create(), Handle: body.Handle, DisplayName: body.DisplayName, CreatedAt: time.Now().UTC()}
	if err := a.d.Store.InsertUser(r.Context(), user); err != nil {
		if store.IsUniqueViolation(err) {
			apierr.Write(w, apierr.Conflict("user:handle:taken", "That handle is taken."))
			return
		}
		apierr.Write(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, toUserDto(user))
}

func (a *App) getUser(w http.ResponseWriter, r *http.Request) {
	user, err := a.d.Store.UserByID(r.Context(), chi.URLParam(r, "id"))
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if user == nil {
		apierr.Write(w, apierr.NotFound("user:not_found", "User not found."))
		return
	}
	writeJSON(w, http.StatusOK, toUserDto(*user))
}
