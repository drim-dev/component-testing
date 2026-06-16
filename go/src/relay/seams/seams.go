// Package seams declares the narrow interfaces each gallery case hides behind (05-gallery
// §0.4). The app's handlers depend on these interfaces, never on concrete types. The
// CORRECT implementations live in src/relay/app (registered in Deps); the NAIVE variants
// live in tests/ and are injected through the SAME constructor seam — in Go that is just a
// struct field swap, no DI framework. That a 404/403 decision is a property of the
// assembled route (not of a mock) is exactly what makes the catching tests catch and the
// lying tests lie.
package seams

import (
	"context"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
)

// DmAccess is the G-IDOR seam: participant-scoped conversation read. Returns nil when the
// caller is not a participant (or the conversation is absent), and the route 404s — hiding
// existence. The naive variant loads by id only.
type DmAccess interface {
	GetForParticipant(ctx context.Context, conversationID, userID string) (*domain.Conversation, error)
}

// ConversationCreateResult carries the conversation and whether this call created it (201)
// or found an existing one (200, idempotent).
type ConversationCreateResult struct {
	Conversation domain.Conversation
	Created      bool
}

// ConversationWriter is the G-RACE / G-TX seam: a transactional, unique-conflict-handling
// create. Concurrent creates for one pair resolve to a single row (RACE); a mid-write
// failure leaves nothing behind (TX). The naive variants are check-then-insert (RACE) and
// three saves with no transaction (TX).
type ConversationWriter interface {
	Create(ctx context.Context, userLo, userHi string) (ConversationCreateResult, error)
}

// ChannelReadGate is the G-BOLA-READ seam: the 404/403 visibility split for reading a
// channel's metadata/messages. The naive variant ignores the private flag.
type ChannelReadGate interface {
	// AuthorizeRead returns the channel if the caller may read it, else an apierr (404 for
	// private/unknown, 403 for public-non-member). isMessages distinguishes metadata
	// (public non-member allowed) from messages (public non-member → 403).
	AuthorizeRead(ctx context.Context, channelID, userID string, isMessages bool) (*domain.Channel, error)
}

// ChannelRoleGate is the G-BOLA-ROLE seam: membership AND role check for admin actions.
// The naive variant checks membership but skips the role compare.
type ChannelRoleGate interface {
	// AuthorizeRole returns nil if the caller is a member with at least minRole in the channel,
	// else an apierr (404 private/unknown, 403 visible-but-forbidden). It also returns the
	// caller's membership so the handler can apply finer rules (e.g. kicking an admin).
	AuthorizeRole(ctx context.Context, channelID, userID string, minRole domain.Role) (*domain.ChannelMember, error)
}

// MembershipWriter is the G-CACHE seam: a membership write coupled to cache invalidation.
// The naive variant writes Postgres and forgets to invalidate the Redis membership cache.
type MembershipWriter interface {
	Add(ctx context.Context, m domain.ChannelMember) error
	Remove(ctx context.Context, channelID, userID string) error
}

// MessagePostedPublisher is the G-KAFKA producer seam: publish awaiting broker
// confirmation (broker down → error → 503, message not persisted). The naive variant fires
// and forgets.
type MessagePostedPublisher interface {
	Publish(ctx context.Context, ev domain.MessagePosted) error
}

// FeedProjector is the G-KAFKA consumer seam: idempotent feed insert + increment-on-first-
// insert. The naive variant inserts and increments unconditionally.
type FeedProjector interface {
	Apply(ctx context.Context, ev domain.MessagePosted) error
}

// NotificationRecorder is the G-RABBIT seam: insert treating a duplicate (unique violation)
// as success so the worker acks. The naive variant never handles the duplicate and crashes.
type NotificationRecorder interface {
	Record(ctx context.Context, job domain.NotificationJob) error
}

// PresenceResult is the channel-presence outcome: the per-member statuses, or Incomplete
// when the stream errored mid-way (→ 502, never a partial list as complete).
type PresenceResult struct {
	Statuses   []domain.PresenceStatus
	Incomplete bool
}

// PresenceClient is the G-GRPC seam: consume the presence stream to clean end; a mid-stream
// error sets Incomplete. The naive variant swallows the error and returns what arrived.
type PresenceClient interface {
	UserPresence(ctx context.Context, userID string) (online bool, err error)
	ChannelPresence(ctx context.Context, userIDs []string) (PresenceResult, error)
}

// Heartbeats marks a user online (TTL 60 s) by writing the SAME Redis key the presence gRPC
// service reads, so a heartbeat is observable through both presence paths.
type Heartbeats interface {
	Mark(ctx context.Context, userID string) error
}

// LinkPreviewer is the G-HTTP seam: fetch a link title with timeout + circuit breaker;
// failure degrades to no title (never escapes). The naive variant has no timeout/guard.
type LinkPreviewer interface {
	// Preview returns the title for url, or nil when the unfurl failed/degraded/breaker-open.
	Preview(ctx context.Context, url string) (*string, error)
}

// SummaryModel is the LLM port (the canonical FAKE): the app never builds a prompt string
// inline — everything crosses this port. The fake verifies the interaction (the captured
// request). A real deployment registers an HTTP-backed model here.
type SummaryModel interface {
	Complete(ctx context.Context, req domain.SummaryRequest) (string, error)
}

// SummarySource is one channel message handed to the Summarizer (sender handle + text).
type SummarySource struct {
	Handle string
	Text   string
}

// Summarizer is the G-LLM seam: it assembles the model request and VALIDATES the output. The
// correct implementation keeps instructions and user content separated (prompt injection) and
// rejects contract-violating output with 502 (never forwards it). The naive variant
// concatenates raw message text into the instruction prompt and returns output unvalidated.
type Summarizer interface {
	Summarize(ctx context.Context, sources []SummarySource) (string, error)
}

// AttachmentAccess is the G-S3 seam: download authorization derives from the attachment's
// CHANNEL MEMBERSHIP, never from possession of the id or storage key. Unknown id and
// private-channel non-member return the same existence-hiding 404; public non-member → 403.
// The naive variant looks the attachment up by id and returns it (possession IS access).
type AttachmentAccess interface {
	Authorize(ctx context.Context, attachmentID, userID string) (*domain.Attachment, error)
}

// AttachmentStore is the object-store port (S3). Bytes live behind an opaque storage key;
// authorization NEVER reads key possession (G-S3) — that is enforced via AttachmentAccess.
type AttachmentStore interface {
	Put(ctx context.Context, key string, data []byte) error
	Get(ctx context.Context, key string) ([]byte, error)
	DeleteAll(ctx context.Context) error
}

// NotificationJobs publishes a DM notification job (RabbitMQ) after the message commits,
// awaiting the broker's publisher confirmation.
type NotificationJobs interface {
	Enqueue(ctx context.Context, job domain.NotificationJob) error
}

// MembershipCache is the Redis authorization fast-path + its invalidation hook.
type MembershipCache interface {
	IsMember(ctx context.Context, channelID, userID string) (cached bool, member bool, err error)
	Remember(ctx context.Context, channelID string, memberIDs []string) error
	Invalidate(ctx context.Context, channelID string) error
}

// UnreadCounters is the Redis per-channel unread counter.
type UnreadCounters interface {
	Increment(ctx context.Context, userID, channelID string) error
	Reset(ctx context.Context, userID, channelID string) error
	ForUser(ctx context.Context, userID string) (map[string]int64, error)
}
