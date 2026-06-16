package app

import (
	"net/http"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/apierr"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/paging"
	"github.com/go-chi/chi/v5"
)

type channelDto struct {
	ID          string    `json:"id"`
	Name        string    `json:"name"`
	Private     bool      `json:"private"`
	MemberCount int       `json:"memberCount,omitempty"`
	CreatedAt   time.Time `json:"createdAt"`
}

type membershipDto struct {
	ChannelID string    `json:"channelId"`
	UserID    string    `json:"userId"`
	Role      string    `json:"role"`
	JoinedAt  time.Time `json:"joinedAt"`
}

func (a *App) createChannel(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	var body struct {
		Name    string `json:"name"`
		Private bool   `json:"private"`
	}
	if err := decodeJSON(r, &body); err != nil {
		apierr.Write(w, err)
		return
	}
	if len([]rune(body.Name)) < 1 || len([]rune(body.Name)) > 100 {
		apierr.Write(w, apierr.Invalid("channel:name:invalid", "name must be 1–100 chars."))
		return
	}

	ch := domain.Channel{ID: a.d.IDs.Create(), Name: body.Name, Private: body.Private, CreatedAt: time.Now().UTC()}
	owner := domain.ChannelMember{ChannelID: ch.ID, UserID: caller.ID, Role: domain.RoleOwner, JoinedAt: time.Now().UTC()}
	if err := a.d.Store.InsertChannelWithOwner(r.Context(), ch, owner); err != nil {
		apierr.Write(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, channelDto{ID: ch.ID, Name: ch.Name, Private: ch.Private, CreatedAt: ch.CreatedAt})
}

func (a *App) listChannels(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	limit, err := paging.ParseLimit(r.URL.Query().Get("limit"))
	if err != nil {
		apierr.Write(w, err)
		return
	}
	before := r.URL.Query().Get("before")
	if before != "" {
		exists, err := a.d.Store.ChannelExists(r.Context(), before)
		if err != nil {
			apierr.Write(w, err)
			return
		}
		if !exists {
			apierr.Write(w, paging.ErrUnknownBefore())
			return
		}
	}
	rows, err := a.d.Store.VisibleChannels(r.Context(), caller.ID, before, limit+1)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	dtos := make([]channelDto, 0, len(rows))
	for _, c := range rows {
		count, err := a.d.Store.MemberCount(r.Context(), c.ID)
		if err != nil {
			apierr.Write(w, err)
			return
		}
		dtos = append(dtos, channelDto{ID: c.ID, Name: c.Name, Private: c.Private, MemberCount: count, CreatedAt: c.CreatedAt})
	}
	writeJSON(w, http.StatusOK, paging.Build(dtos, limit, func(d channelDto) string { return d.ID }))
}

func (a *App) getChannel(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	ch, err := a.d.ChannelReadGate.AuthorizeRead(r.Context(), chi.URLParam(r, "id"), caller.ID, false)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	count, err := a.d.Store.MemberCount(r.Context(), ch.ID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	writeJSON(w, http.StatusOK, channelDto{ID: ch.ID, Name: ch.Name, Private: ch.Private, MemberCount: count, CreatedAt: ch.CreatedAt})
}

func (a *App) joinChannel(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	channelID := chi.URLParam(r, "id")
	ch, err := a.d.Store.ChannelByID(r.Context(), channelID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if ch == nil {
		apierr.Write(w, notFoundChannel())
		return
	}
	member, err := a.d.Store.Membership(r.Context(), channelID, caller.ID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if member != nil {
		apierr.Write(w, apierr.Conflict("channel:member:already", "You are already a member."))
		return
	}
	if ch.Private {
		apierr.Write(w, notFoundChannel())
		return
	}
	m := domain.ChannelMember{ChannelID: channelID, UserID: caller.ID, Role: domain.RoleMember, JoinedAt: time.Now().UTC()}
	if err := a.d.MembershipWriter.Add(r.Context(), m); err != nil {
		apierr.Write(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, membershipDto{ChannelID: channelID, UserID: caller.ID, Role: "member", JoinedAt: m.JoinedAt})
}

func (a *App) addMember(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	channelID := chi.URLParam(r, "id")
	if _, err := a.d.ChannelRoleGate.AuthorizeRole(r.Context(), channelID, caller.ID, domain.RoleAdmin); err != nil {
		apierr.Write(w, err)
		return
	}
	var body struct {
		UserID string `json:"userId"`
	}
	if err := decodeJSON(r, &body); err != nil {
		apierr.Write(w, err)
		return
	}
	target, err := a.d.Store.UserByID(r.Context(), body.UserID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if target == nil {
		apierr.Write(w, apierr.NotFound("user:not_found", "User not found."))
		return
	}
	existing, err := a.d.Store.Membership(r.Context(), channelID, body.UserID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if existing != nil {
		apierr.Write(w, apierr.Conflict("channel:member:already", "That user is already a member."))
		return
	}
	m := domain.ChannelMember{ChannelID: channelID, UserID: body.UserID, Role: domain.RoleMember, JoinedAt: time.Now().UTC()}
	if err := a.d.MembershipWriter.Add(r.Context(), m); err != nil {
		apierr.Write(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, membershipDto{ChannelID: channelID, UserID: body.UserID, Role: "member", JoinedAt: m.JoinedAt})
}

func (a *App) promoteMember(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	channelID := chi.URLParam(r, "id")
	targetID := chi.URLParam(r, "userId")
	if _, err := a.d.ChannelRoleGate.AuthorizeRole(r.Context(), channelID, caller.ID, domain.RoleOwner); err != nil {
		apierr.Write(w, err)
		return
	}
	target, err := a.d.Store.Membership(r.Context(), channelID, targetID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if target == nil {
		apierr.Write(w, apierr.NotFound("channel:member:not_found", "Member not found."))
		return
	}
	if target.Role.AtLeast(domain.RoleAdmin) {
		apierr.Write(w, apierr.Conflict("channel:member:already", "That member is already an admin or owner."))
		return
	}
	if err := a.d.Store.UpdateMemberRole(r.Context(), channelID, targetID, domain.RoleAdmin); err != nil {
		apierr.Write(w, err)
		return
	}
	writeJSON(w, http.StatusOK, membershipDto{ChannelID: channelID, UserID: targetID, Role: "admin", JoinedAt: target.JoinedAt})
}

func (a *App) removeMember(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	channelID := chi.URLParam(r, "id")
	targetID := chi.URLParam(r, "userId")

	if targetID == caller.ID {
		a.leaveChannel(w, r, channelID, caller.ID)
		return
	}
	a.kickMember(w, r, channelID, caller.ID, targetID)
}

func (a *App) leaveChannel(w http.ResponseWriter, r *http.Request, channelID, callerID string) {
	ch, err := a.d.Store.ChannelByID(r.Context(), channelID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if ch == nil {
		apierr.Write(w, notFoundChannel())
		return
	}
	member, err := a.d.Store.Membership(r.Context(), channelID, callerID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if member == nil {
		if ch.Private {
			apierr.Write(w, notFoundChannel())
			return
		}
		apierr.Write(w, apierr.NotFound("channel:member:not_found", "Member not found."))
		return
	}
	if member.Role == domain.RoleOwner {
		apierr.Write(w, apierr.Conflict("channel:owner:cannot_leave", "The owner cannot leave their own channel."))
		return
	}
	if err := a.d.MembershipWriter.Remove(r.Context(), channelID, callerID); err != nil {
		apierr.Write(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) kickMember(w http.ResponseWriter, r *http.Request, channelID, callerID, targetID string) {
	callerMembership, err := a.d.ChannelRoleGate.AuthorizeRole(r.Context(), channelID, callerID, domain.RoleAdmin)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	target, err := a.d.Store.Membership(r.Context(), channelID, targetID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if target == nil {
		apierr.Write(w, apierr.NotFound("channel:member:not_found", "Member not found."))
		return
	}
	kickingPrivileged := target.Role == domain.RoleOwner ||
		(target.Role == domain.RoleAdmin && callerMembership.Role != domain.RoleOwner)
	if kickingPrivileged {
		apierr.Write(w, apierr.Forbidden("channel:role:forbidden", "Your role does not permit removing this member."))
		return
	}
	if err := a.d.MembershipWriter.Remove(r.Context(), channelID, targetID); err != nil {
		apierr.Write(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) deleteChannel(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	channelID := chi.URLParam(r, "id")
	if _, err := a.d.ChannelRoleGate.AuthorizeRole(r.Context(), channelID, caller.ID, domain.RoleOwner); err != nil {
		apierr.Write(w, err)
		return
	}
	if err := a.d.Store.DeleteChannel(r.Context(), channelID); err != nil {
		apierr.Write(w, err)
		return
	}
	if err := a.d.Cache.Invalidate(r.Context(), channelID); err != nil {
		apierr.Write(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *App) getChannelMessages(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	channelID := chi.URLParam(r, "id")
	if _, err := a.d.ChannelReadGate.AuthorizeRead(r.Context(), channelID, caller.ID, true); err != nil {
		apierr.Write(w, err)
		return
	}
	limit, err := paging.ParseLimit(r.URL.Query().Get("limit"))
	if err != nil {
		apierr.Write(w, err)
		return
	}
	before := r.URL.Query().Get("before")
	if before != "" {
		exists, err := a.d.Store.ChannelMessageExists(r.Context(), channelID, before)
		if err != nil {
			apierr.Write(w, err)
			return
		}
		if !exists {
			apierr.Write(w, paging.ErrUnknownBefore())
			return
		}
	}
	rows, err := a.d.Store.ChannelMessages(r.Context(), channelID, before, limit+1)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	dtos := make([]channelMessageDto, 0, len(rows))
	for _, m := range rows {
		dtos = append(dtos, toChannelMessageDto(m, nil))
	}
	writeJSON(w, http.StatusOK, paging.Build(dtos, limit, func(d channelMessageDto) string { return d.ID }))
}

func (a *App) markChannelRead(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	channelID := chi.URLParam(r, "id")
	if _, err := a.d.ChannelReadGate.AuthorizeRead(r.Context(), channelID, caller.ID, true); err != nil {
		apierr.Write(w, err)
		return
	}
	if err := a.d.Unread.Reset(r.Context(), caller.ID, channelID); err != nil {
		apierr.Write(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
