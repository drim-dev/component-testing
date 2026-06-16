package app

import "github.com/go-chi/chi/v5"

// routes maps every endpoint in 02-api.md. Handlers translate seam results into the pinned
// status codes; the seams own the authorization decisions.
func (a *App) routes(r chi.Router) {
	r.Post("/users", a.createUser)
	r.Get("/users/{id}", a.getUser)

	r.Post("/dm/conversations", a.createConversation)
	r.Get("/dm/conversations", a.listConversations)
	r.Get("/dm/conversations/{id}", a.getConversation)
	r.Post("/dm/conversations/{id}/messages", a.createDmMessage)
	r.Get("/dm/conversations/{id}/messages", a.getDmMessages)

	r.Post("/channels", a.createChannel)
	r.Get("/channels", a.listChannels)
	r.Get("/channels/{id}", a.getChannel)
	r.Post("/channels/{id}/join", a.joinChannel)
	r.Post("/channels/{id}/members", a.addMember)
	r.Post("/channels/{id}/members/{userId}/promote", a.promoteMember)
	r.Delete("/channels/{id}/members/{userId}", a.removeMember)
	r.Delete("/channels/{id}", a.deleteChannel)
	r.Post("/channels/{id}/messages", a.postChannelMessage)
	r.Get("/channels/{id}/messages", a.getChannelMessages)
	r.Post("/channels/{id}/read", a.markChannelRead)
	r.Post("/channels/{id}/summary", a.getSummary)

	r.Post("/channels/{id}/attachments", a.uploadAttachment)
	r.Get("/attachments/{id}", a.downloadAttachment)

	r.Get("/notifications", a.getNotifications)
	r.Get("/feed", a.getFeed)
	r.Get("/me/unread", a.getUnread)

	r.Post("/me/heartbeat", a.heartbeat)
	r.Get("/users/{id}/presence", a.getUserPresence)
	r.Get("/channels/{id}/presence", a.getChannelPresence)

	r.Get("/links/preview", a.getLinkPreview)
}
