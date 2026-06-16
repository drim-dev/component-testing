package app

import (
	"net/http"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/apierr"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/paging"
	"github.com/go-chi/chi/v5"
)

type conversationDto struct {
	ID             string    `json:"id"`
	ParticipantIDs []string  `json:"participantIds"`
	CreatedAt      time.Time `json:"createdAt"`
}

func toConversationDto(c domain.Conversation) conversationDto {
	return conversationDto{ID: c.ID, ParticipantIDs: []string{c.UserLo, c.UserHi}, CreatedAt: c.CreatedAt}
}

type dmMessageDto struct {
	ID             string    `json:"id"`
	ConversationID string    `json:"conversationId"`
	SenderID       string    `json:"senderId"`
	Text           string    `json:"text"`
	CreatedAt      time.Time `json:"createdAt"`
}

func (a *App) createConversation(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	var body struct {
		RecipientID string `json:"recipientId"`
	}
	if err := decodeJSON(r, &body); err != nil {
		apierr.Write(w, err)
		return
	}
	if body.RecipientID == caller.ID {
		apierr.Write(w, apierr.Invalid("dm:recipient:self", "You cannot open a conversation with yourself."))
		return
	}
	recipient, err := a.d.Store.UserByID(r.Context(), body.RecipientID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if recipient == nil {
		apierr.Write(w, apierr.NotFound("user:not_found", "User not found."))
		return
	}

	lo, hi := domain.NormalizePair(caller.ID, recipient.ID)
	result, err := a.d.ConversationWriter.Create(r.Context(), lo, hi)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	status := http.StatusOK
	if result.Created {
		status = http.StatusCreated
	}
	writeJSON(w, status, toConversationDto(result.Conversation))
}

func (a *App) listConversations(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	limit, err := paging.ParseLimit(r.URL.Query().Get("limit"))
	if err != nil {
		apierr.Write(w, err)
		return
	}
	before := r.URL.Query().Get("before")
	if err := a.ensureConversationCursor(r, before); err != nil {
		apierr.Write(w, err)
		return
	}
	rows, err := a.d.Store.ConversationsFor(r.Context(), caller.ID, before, limit+1)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	dtos := make([]conversationDto, 0, len(rows))
	for _, c := range rows {
		dtos = append(dtos, toConversationDto(c))
	}
	writeJSON(w, http.StatusOK, paging.Build(dtos, limit, func(d conversationDto) string { return d.ID }))
}

func (a *App) ensureConversationCursor(r *http.Request, before string) error {
	if before == "" {
		return nil
	}
	exists, err := a.d.Store.ConversationExists(r.Context(), before)
	if err != nil {
		return err
	}
	if !exists {
		return paging.ErrUnknownBefore()
	}
	return nil
}

func (a *App) getConversation(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	conv, err := a.d.DmAccess.GetForParticipant(r.Context(), chi.URLParam(r, "id"), caller.ID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if conv == nil {
		apierr.Write(w, notFoundConversation())
		return
	}
	writeJSON(w, http.StatusOK, toConversationDto(*conv))
}

func (a *App) createDmMessage(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	conversationID := chi.URLParam(r, "id")
	conv, err := a.d.DmAccess.GetForParticipant(r.Context(), conversationID, caller.ID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if conv == nil {
		apierr.Write(w, notFoundConversation())
		return
	}
	var body struct {
		Text string `json:"text"`
	}
	if err := decodeJSON(r, &body); err != nil {
		apierr.Write(w, err)
		return
	}
	if len(body.Text) < 1 || len([]rune(body.Text)) > 4000 {
		apierr.Write(w, apierr.Invalid("message:text:invalid", "text must be 1–4000 chars."))
		return
	}

	msg := domain.DmMessage{
		ID: a.d.IDs.Create(), ConversationID: conversationID, SenderID: caller.ID,
		Text: body.Text, CreatedAt: time.Now().UTC(),
	}
	if err := a.d.Store.InsertDmMessage(r.Context(), msg); err != nil {
		apierr.Write(w, err)
		return
	}

	// Pinned ordering (02-api.md §2): the notification job is enqueued AFTER the message
	// transaction commits, awaiting the broker's publisher confirmation. A publish failure
	// here is a 500 — the message stays. This avoids the worker racing an uncommitted row.
	recipient := conv.UserLo
	if recipient == caller.ID {
		recipient = conv.UserHi
	}
	job := domain.NotificationJob{
		DmMessageID: msg.ID, ConversationID: conversationID, SenderID: caller.ID,
		RecipientID: recipient, Preview: domain.Preview(body.Text),
	}
	if err := a.d.Jobs.Enqueue(r.Context(), job); err != nil {
		apierr.Write(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, dmMessageDto{
		ID: msg.ID, ConversationID: conversationID, SenderID: caller.ID, Text: body.Text, CreatedAt: msg.CreatedAt,
	})
}

func (a *App) getDmMessages(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	conversationID := chi.URLParam(r, "id")
	conv, err := a.d.DmAccess.GetForParticipant(r.Context(), conversationID, caller.ID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if conv == nil {
		apierr.Write(w, notFoundConversation())
		return
	}
	limit, err := paging.ParseLimit(r.URL.Query().Get("limit"))
	if err != nil {
		apierr.Write(w, err)
		return
	}
	before := r.URL.Query().Get("before")
	if before != "" {
		exists, err := a.d.Store.DmMessageExists(r.Context(), conversationID, before)
		if err != nil {
			apierr.Write(w, err)
			return
		}
		if !exists {
			apierr.Write(w, paging.ErrUnknownBefore())
			return
		}
	}
	rows, err := a.d.Store.DmMessages(r.Context(), conversationID, before, limit+1)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	dtos := make([]dmMessageDto, 0, len(rows))
	for _, m := range rows {
		dtos = append(dtos, dmMessageDto{ID: m.ID, ConversationID: m.ConversationID, SenderID: m.SenderID, Text: m.Text, CreatedAt: m.CreatedAt})
	}
	writeJSON(w, http.StatusOK, paging.Build(dtos, limit, func(d dmMessageDto) string { return d.ID }))
}
