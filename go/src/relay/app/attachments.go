package app

import (
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/apierr"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/go-chi/chi/v5"
)

const maxAttachmentBytes = 1 << 20 // 1 MiB

type attachmentDto struct {
	ID        string    `json:"id"`
	ChannelID string    `json:"channelId"`
	Filename  string    `json:"filename"`
	SizeBytes int64     `json:"sizeBytes"`
	CreatedAt time.Time `json:"createdAt"`
}

func (a *App) uploadAttachment(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	channelID := chi.URLParam(r, "id")
	if _, err := a.d.ChannelRoleGate.AuthorizeRole(r.Context(), channelID, caller.ID, domain.RoleMember); err != nil {
		apierr.Write(w, err)
		return
	}

	// Read one extra byte past the limit so an over-1-MiB file is detected, not truncated.
	if err := r.ParseMultipartForm(maxAttachmentBytes + 1024); err != nil {
		apierr.Write(w, apierr.Invalid("attachment:invalid", "Could not read the upload."))
		return
	}
	file, header, err := r.FormFile("file")
	if err != nil {
		apierr.Write(w, apierr.Invalid("attachment:invalid", "A file field is required."))
		return
	}
	defer func() { _ = file.Close() }()

	content, err := io.ReadAll(io.LimitReader(file, maxAttachmentBytes+1))
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if len(content) > maxAttachmentBytes {
		apierr.Write(w, apierr.TooLarge("attachment:too_large", "The attachment exceeds the 1 MiB limit."))
		return
	}
	if len(content) == 0 {
		apierr.Write(w, apierr.Invalid("attachment:empty", "The attachment is empty."))
		return
	}

	id := a.d.IDs.Create()
	storageKey := fmt.Sprintf("%s/%s", channelID, id)
	if err := a.d.Store3.Put(r.Context(), storageKey, content); err != nil {
		apierr.Write(w, err)
		return
	}
	att := domain.Attachment{
		ID: id, ChannelID: channelID, UploaderID: caller.ID, Filename: header.Filename,
		SizeBytes: int64(len(content)), StorageKey: storageKey, CreatedAt: time.Now().UTC(),
	}
	if err := a.d.Store.InsertAttachment(r.Context(), att); err != nil {
		apierr.Write(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, attachmentDto{
		ID: att.ID, ChannelID: att.ChannelID, Filename: att.Filename, SizeBytes: att.SizeBytes, CreatedAt: att.CreatedAt,
	})
}

func (a *App) downloadAttachment(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	att, err := a.d.AttachmentAccess.Authorize(r.Context(), chi.URLParam(r, "id"), caller.ID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	content, err := a.d.Store3.Get(r.Context(), att.StorageKey)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", fmt.Sprintf("attachment; filename=%q", att.Filename))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(content)
}
