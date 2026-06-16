package app

import (
	"net/http"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/apierr"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/go-chi/chi/v5"
)

type channelMessageDto struct {
	ID               string    `json:"id"`
	ChannelID        string    `json:"channelId"`
	SenderID         string    `json:"senderId"`
	Text             string    `json:"text"`
	AttachmentIDs    []string  `json:"attachmentIds"`
	LinkPreviewTitle *string   `json:"linkPreviewTitle"`
	CreatedAt        time.Time `json:"createdAt"`
}

func toChannelMessageDto(m domain.ChannelMessage, attachmentIDs []string) channelMessageDto {
	if attachmentIDs == nil {
		attachmentIDs = []string{}
	}
	return channelMessageDto{
		ID: m.ID, ChannelID: m.ChannelID, SenderID: m.SenderID, Text: m.Text,
		AttachmentIDs: attachmentIDs, LinkPreviewTitle: m.LinkPreviewTitle, CreatedAt: m.CreatedAt,
	}
}

func (a *App) postChannelMessage(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	channelID := chi.URLParam(r, "id")
	if _, err := a.d.ChannelRoleGate.AuthorizeRole(r.Context(), channelID, caller.ID, domain.RoleMember); err != nil {
		apierr.Write(w, err)
		return
	}
	var body struct {
		Text          string   `json:"text"`
		AttachmentIDs []string `json:"attachmentIds"`
	}
	if err := decodeJSON(r, &body); err != nil {
		apierr.Write(w, err)
		return
	}
	if len(body.Text) < 1 || len([]rune(body.Text)) > 4000 {
		apierr.Write(w, apierr.Invalid("message:text:invalid", "text must be 1–4000 chars."))
		return
	}
	if len(body.AttachmentIDs) > 10 {
		apierr.Write(w, apierr.Invalid("message:attachment:invalid", "A message can reference at most 10 attachments."))
		return
	}
	if err := a.validateAttachments(r, channelID, caller.ID, body.AttachmentIDs); err != nil {
		apierr.Write(w, err)
		return
	}

	// Unfurl runs (bounded, graceful) BEFORE the insert so the title persists with the row;
	// a slow/failing upstream degrades to a null title, never a hang (G-HTTP).
	var title *string
	if url := domain.FirstURL(body.Text); url != "" {
		t, err := a.d.LinkPreviewer.Preview(r.Context(), url)
		if err != nil {
			apierr.Write(w, err)
			return
		}
		title = t
	}

	msg := domain.ChannelMessage{
		ID: a.d.IDs.Create(), ChannelID: channelID, SenderID: caller.ID, Text: body.Text,
		LinkPreviewTitle: title, CreatedAt: time.Now().UTC(),
	}

	// Pinned write ordering (02-api.md §3, no outbox): open tx → insert message → publish
	// AWAITING broker confirmation → commit. A publish failure rolls back and surfaces 503;
	// the message is never half-posted (G-KAFKA producer).
	tx, err := a.d.Store.Pool().Begin(r.Context())
	if err != nil {
		apierr.Write(w, err)
		return
	}
	committed := false
	defer func() {
		if !committed {
			_ = tx.Rollback(r.Context())
		}
	}()

	if err := a.d.Store.InsertChannelMessage(r.Context(), tx, msg); err != nil {
		apierr.Write(w, err)
		return
	}
	if err := a.d.Store.AttachMessageToAttachments(r.Context(), tx, msg.ID, body.AttachmentIDs); err != nil {
		apierr.Write(w, err)
		return
	}

	ev := domain.MessagePosted{
		MessageID: msg.ID, ChannelID: channelID, SenderID: caller.ID,
		Preview: domain.Preview(body.Text), PostedAt: msg.CreatedAt,
	}
	if err := a.d.Publisher.Publish(r.Context(), ev); err != nil {
		apierr.Write(w, err) // 503 events:unavailable; tx rolls back, nothing persisted
		return
	}
	if err := tx.Commit(r.Context()); err != nil {
		apierr.Write(w, err)
		return
	}
	committed = true

	writeJSON(w, http.StatusCreated, toChannelMessageDto(msg, body.AttachmentIDs))
}

// validateAttachments enforces S-AT-04: every referenced attachment must be uploaded by the
// caller to THIS channel (and not yet bound). A bad reference is 422 before any write.
func (a *App) validateAttachments(r *http.Request, channelID, callerID string, ids []string) error {
	if len(ids) == 0 {
		return nil
	}
	owned, err := a.d.Store.AttachmentsOwnedInChannel(r.Context(), channelID, callerID, ids)
	if err != nil {
		return err
	}
	if len(owned) != len(ids) {
		return apierr.Invalid("message:attachment:invalid",
			"Attachments must be uploaded to this channel by you and not already referenced.")
	}
	return nil
}
