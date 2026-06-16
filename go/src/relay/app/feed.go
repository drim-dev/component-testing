package app

import (
	"net/http"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/apierr"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/paging"
)

type notificationDto struct {
	ID             string    `json:"id"`
	Type           string    `json:"type"`
	DmMessageID    string    `json:"dmMessageId"`
	ConversationID string    `json:"conversationId"`
	SenderID       string    `json:"senderId"`
	Preview        string    `json:"preview"`
	CreatedAt      time.Time `json:"createdAt"`
}

type feedEntryDto struct {
	ChannelID string    `json:"channelId"`
	MessageID string    `json:"messageId"`
	SenderID  string    `json:"senderId"`
	Preview   string    `json:"preview"`
	CreatedAt time.Time `json:"createdAt"`
}

func (a *App) getNotifications(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	limit, err := paging.ParseLimit(r.URL.Query().Get("limit"))
	if err != nil {
		apierr.Write(w, err)
		return
	}
	rows, err := a.d.Store.NotificationsFor(r.Context(), caller.ID, r.URL.Query().Get("before"), limit+1)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	dtos := make([]notificationDto, 0, len(rows))
	for _, n := range rows {
		dtos = append(dtos, notificationDto{
			ID: n.ID, Type: "dm.message", DmMessageID: n.DmMessageID, ConversationID: n.ConversationID,
			SenderID: n.SenderID, Preview: n.Preview, CreatedAt: n.CreatedAt,
		})
	}
	writeJSON(w, http.StatusOK, paging.Build(dtos, limit, func(d notificationDto) string { return d.ID }))
}

func (a *App) getFeed(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	limit, err := paging.ParseLimit(r.URL.Query().Get("limit"))
	if err != nil {
		apierr.Write(w, err)
		return
	}
	rows, err := a.d.Store.FeedFor(r.Context(), caller.ID, r.URL.Query().Get("before"), limit+1)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	dtos := make([]feedEntryDto, 0, len(rows))
	ids := make([]string, 0, len(rows))
	for _, f := range rows {
		dtos = append(dtos, feedEntryDto{ChannelID: f.ChannelID, MessageID: f.MessageID, SenderID: f.SenderID, Preview: f.Preview, CreatedAt: f.CreatedAt})
		ids = append(ids, f.ID)
	}
	writeJSON(w, http.StatusOK, buildFeedPage(dtos, ids, limit))
}

// buildFeedPage paginates feed DTOs whose cursor (the feed_entries.id) is not part of the
// DTO shape, so it is carried alongside.
func buildFeedPage(dtos []feedEntryDto, ids []string, limit int) paging.Page[feedEntryDto] {
	if len(dtos) > limit {
		next := ids[limit-1]
		return paging.Page[feedEntryDto]{Items: dtos[:limit], NextBefore: &next}
	}
	if dtos == nil {
		dtos = []feedEntryDto{}
	}
	return paging.Page[feedEntryDto]{Items: dtos, NextBefore: nil}
}

func (a *App) getUnread(w http.ResponseWriter, r *http.Request) {
	caller := currentUser(r)
	counts, err := a.d.Unread.ForUser(r.Context(), caller.ID)
	if err != nil {
		apierr.Write(w, err)
		return
	}
	if counts == nil {
		counts = map[string]int64{}
	}
	writeJSON(w, http.StatusOK, map[string]any{"channels": counts})
}
