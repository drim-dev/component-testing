package domain

import "time"

// MessagePosted is the Kafka event (topic message-posted, key = ChannelID) fanned out to
// members' feeds + unread counters.
type MessagePosted struct {
	MessageID string    `json:"messageId"`
	ChannelID string    `json:"channelId"`
	SenderID  string    `json:"senderId"`
	Preview   string    `json:"preview"`
	PostedAt  time.Time `json:"postedAt"`
}

// NotificationJob is the RabbitMQ job (queue notify.dm) the worker turns into a notification
// row, exactly once per DM message under at-least-once redelivery.
type NotificationJob struct {
	DmMessageID    string `json:"dmMessageId"`
	ConversationID string `json:"conversationId"`
	SenderID       string `json:"senderId"`
	RecipientID    string `json:"recipientId"`
	Preview        string `json:"preview"`
}

// PresenceStatus is one member's presence (from the gRPC stream / unary RPC).
type PresenceStatus struct {
	UserID string
	Online bool
}

// SummaryRequest is what the app hands the SummaryModel: a constant system prompt plus the
// messages as already-rendered, delimited DATA blocks. The fake verifies the system prompt
// equals the pinned constant and that hostile text appears ONLY inside a block (G-LLM).
type SummaryRequest struct {
	SystemPrompt  string
	MessageBlocks []string
}
