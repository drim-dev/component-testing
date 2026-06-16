package app

import (
	"net/http"
	"sort"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/apierr"
	"github.com/go-chi/chi/v5"
)

func (a *App) heartbeat(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	if err := a.d.Heartbeats.Mark(r.Context(), caller.ID); err != nil {
		apierr.Write(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) getUserPresence(w http.ResponseWriter, r *http.Request) {
	userID := chi.URLParam(r, "id")
	user, err := a.d.Store.UserByID(r.Context(), userID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if user == nil {
		apierr.Write(w, apierr.NotFound("user:not_found", "User not found."))
		return
	}
	online, err := a.d.Presence.UserPresence(r.Context(), userID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"userId": userID, "status": statusOf(online)})
}

func (a *App) getChannelPresence(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	channelID := chi.URLParam(r, "id")
	// Same visibility as message-read: member 200; public non-member 403; private/unknown 404.
	if _, err := a.d.ChannelReadGate.AuthorizeRead(r.Context(), channelID, caller.ID, true); err != nil {
		apierr.Write(w, err)
		return
	}
	memberIDs, err := a.d.Store.MemberIDsExcept(r.Context(), channelID, "")
	if err != nil {
		apierr.Write(w, err)
		return
	}
	sort.Strings(memberIDs)

	result, err := a.d.Presence.ChannelPresence(r.Context(), memberIDs)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if result.Incomplete {
		apierr.Write(w, apierr.Upstream("presence:incomplete", "The presence stream terminated before completion."))
		return
	}
	members := make([]map[string]string, 0, len(result.Statuses))
	for _, s := range result.Statuses {
		members = append(members, map[string]string{"userId": s.UserID, "status": statusOf(s.Online)})
	}
	writeJSON(w, http.StatusOK, map[string]any{"members": members})
}

func statusOf(online bool) string {
	if online {
		return "online"
	}
	return "offline"
}
