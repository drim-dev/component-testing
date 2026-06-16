package app

import (
	"context"
	"strings"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/apierr"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/idgen"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/seams"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/store"
)

// notFoundConversation is the existence-hiding 404 for DMs — the SAME code+message for an
// unknown id and for a non-participant, so the JSON bodies are byte-identical (G-IDOR).
func notFoundConversation() error {
	return apierr.NotFound("dm:conversation:not_found", "Conversation not found.")
}

func notFoundChannel() error {
	return apierr.NotFound("channel:not_found", "Channel not found.")
}

// ---- G-IDOR: correct DM access ----

type dmAccess struct{ store *store.Store }

// NewDmAccess is the correct G-IDOR seam: load the conversation, then APPLY the participant
// predicate. A non-participant gets nil → the route 404s. The naive variant skips the check.
func NewDmAccess(s *store.Store) seams.DmAccess { return &dmAccess{store: s} }

func (a *dmAccess) GetForParticipant(ctx context.Context, conversationID, userID string) (*domain.Conversation, error) {
	c, err := a.store.ConversationByID(ctx, conversationID)
	if err != nil {
		return nil, err
	}
	if c == nil || !c.IsParticipant(userID) {
		return nil, nil
	}
	return c, nil
}

// ---- G-RACE / G-TX: correct transactional conversation writer ----

type conversationWriter struct {
	store *store.Store
	ids   *idgen.Factory
}

// NewConversationWriter is the correct G-RACE/G-TX seam: one transaction inserts the
// conversation + both participant rows, and a unique-pair violation (the concurrent loser)
// is recovered by reading back the winner's row. Timing-independent — no test hook needed.
func NewConversationWriter(s *store.Store, ids *idgen.Factory) seams.ConversationWriter {
	return &conversationWriter{store: s, ids: ids}
}

func (w *conversationWriter) Create(ctx context.Context, userLo, userHi string) (seams.ConversationCreateResult, error) {
	if existing, err := w.store.ConversationByPair(ctx, userLo, userHi); err != nil {
		return seams.ConversationCreateResult{}, err
	} else if existing != nil {
		return seams.ConversationCreateResult{Conversation: *existing, Created: false}, nil
	}

	conv := domain.Conversation{ID: w.ids.Create(), UserLo: userLo, UserHi: userHi, CreatedAt: time.Now().UTC()}
	err := w.insertAtomically(ctx, conv)
	if err == nil {
		return seams.ConversationCreateResult{Conversation: conv, Created: true}, nil
	}
	if store.IsUniqueViolation(err) {
		// A concurrent create won the unique-pair race; return its row (idempotent).
		winner, lookupErr := w.store.ConversationByPair(ctx, userLo, userHi)
		if lookupErr != nil {
			return seams.ConversationCreateResult{}, lookupErr
		}
		if winner != nil {
			return seams.ConversationCreateResult{Conversation: *winner, Created: false}, nil
		}
	}
	return seams.ConversationCreateResult{}, err
}

func (w *conversationWriter) insertAtomically(ctx context.Context, conv domain.Conversation) error {
	tx, err := w.store.Pool().Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()

	if _, err := tx.Exec(ctx,
		`INSERT INTO dm_conversations (id, user_lo, user_hi, created_at) VALUES ($1, $2, $3, $4)`,
		conv.ID, conv.UserLo, conv.UserHi, conv.CreatedAt); err != nil {
		return err
	}
	for _, uid := range []string{conv.UserLo, conv.UserHi} {
		if _, err := tx.Exec(ctx,
			`INSERT INTO dm_participants (conversation_id, user_id) VALUES ($1, $2)`,
			conv.ID, uid); err != nil {
			return err
		}
	}
	return tx.Commit(ctx)
}

// ---- G-BOLA-READ: correct channel read gate ----

type channelReadGate struct{ store *store.Store }

// NewChannelReadGate is the correct G-BOLA-READ seam: the 404/403 visibility split.
// Private + non-member → 404 (existence hidden). Public + non-member → 200 metadata but 403
// for messages. The naive variant never consults the private flag for the caller.
func NewChannelReadGate(s *store.Store) seams.ChannelReadGate { return &channelReadGate{store: s} }

func (g *channelReadGate) AuthorizeRead(ctx context.Context, channelID, userID string, isMessages bool) (*domain.Channel, error) {
	ch, err := g.store.ChannelByID(ctx, channelID)
	if err != nil {
		return nil, err
	}
	if ch == nil {
		return nil, notFoundChannel()
	}
	member, err := g.store.Membership(ctx, channelID, userID)
	if err != nil {
		return nil, err
	}
	if member != nil {
		return ch, nil
	}
	if ch.Private {
		return nil, notFoundChannel()
	}
	if isMessages {
		return nil, apierr.Forbidden("channel:membership_required", "Membership is required to read messages.")
	}
	return ch, nil
}

// ---- G-BOLA-ROLE: correct channel role gate ----

type channelRoleGate struct{ store *store.Store }

// NewChannelRoleGate is the correct G-BOLA-ROLE seam: membership AND the role compare.
// A plain member attempting an admin action gets 403 (visible-but-forbidden); a non-member
// gets 404 (private) / 403 (public). The naive variant checks membership but skips the role.
func NewChannelRoleGate(s *store.Store) seams.ChannelRoleGate { return &channelRoleGate{store: s} }

func (g *channelRoleGate) AuthorizeRole(ctx context.Context, channelID, userID string, minRole domain.Role) (*domain.ChannelMember, error) {
	ch, err := g.store.ChannelByID(ctx, channelID)
	if err != nil {
		return nil, err
	}
	if ch == nil {
		return nil, notFoundChannel()
	}
	member, err := g.store.Membership(ctx, channelID, userID)
	if err != nil {
		return nil, err
	}
	if member == nil {
		if ch.Private {
			return nil, notFoundChannel()
		}
		return nil, apierr.Forbidden("channel:membership_required", "Membership is required.")
	}
	if !member.Role.AtLeast(minRole) {
		return nil, apierr.Forbidden("channel:role:forbidden", "Your role does not permit this action.")
	}
	return member, nil
}

// ---- G-CACHE: correct membership writer (write + invalidate) ----

type membershipWriter struct {
	store *store.Store
	cache seams.MembershipCache
}

// NewMembershipWriter is the correct G-CACHE seam: a membership write (add/remove) coupled
// to invalidating the Redis membership cache, so a removed member's next read is denied
// immediately. The naive variant writes Postgres and forgets the invalidation.
func NewMembershipWriter(s *store.Store, cache seams.MembershipCache) seams.MembershipWriter {
	return &membershipWriter{store: s, cache: cache}
}

func (w *membershipWriter) Add(ctx context.Context, m domain.ChannelMember) error {
	if err := w.store.InsertMember(ctx, m); err != nil {
		return err
	}
	return w.cache.Invalidate(ctx, m.ChannelID)
}

func (w *membershipWriter) Remove(ctx context.Context, channelID, userID string) error {
	if err := w.store.DeleteMember(ctx, channelID, userID); err != nil {
		return err
	}
	return w.cache.Invalidate(ctx, channelID)
}

// ---- G-KAFKA consumer: correct feed projector ----

type feedProjector struct {
	store  *store.Store
	unread seams.UnreadCounters
	ids    *idgen.Factory
}

// NewFeedProjector is the correct G-KAFKA consumer seam: idempotent per (user, message).
// The UNIQUE (user_id, message_id) constraint is the backstop, and the unread counter is
// incremented ONLY on a first successful insert — so feed and counter never diverge under
// redelivery. The naive variant inserts and increments unconditionally.
func NewFeedProjector(s *store.Store, unread seams.UnreadCounters, ids *idgen.Factory) seams.FeedProjector {
	return &feedProjector{store: s, unread: unread, ids: ids}
}

func (p *feedProjector) Apply(ctx context.Context, ev domain.MessagePosted) error {
	memberIDs, err := p.store.MemberIDsExcept(ctx, ev.ChannelID, ev.SenderID)
	if err != nil {
		return err
	}
	for _, memberID := range memberIDs {
		entry := domain.FeedEntry{
			ID: p.ids.Create(), UserID: memberID, ChannelID: ev.ChannelID, MessageID: ev.MessageID,
			SenderID: ev.SenderID, Preview: ev.Preview, CreatedAt: ev.PostedAt,
		}
		err := p.store.InsertFeedEntry(ctx, entry)
		if err == nil {
			if err := p.unread.Increment(ctx, memberID, ev.ChannelID); err != nil {
				return err
			}
			continue
		}
		if store.IsUniqueViolation(err) {
			continue // already projected — do NOT increment again
		}
		return err
	}
	return nil
}

// ---- G-RABBIT: correct notification recorder ----

type notificationRecorder struct {
	store *store.Store
	ids   *idgen.Factory
}

// NewNotificationRecorder is the correct G-RABBIT seam: insert, treating the
// UNIQUE(dm_message_id) violation (a redelivered duplicate) as SUCCESS so the worker acks.
// A genuine failure (poison job — unresolvable recipient FK) bubbles up to be retried then
// dead-lettered. The naive variant never handles the duplicate and crash-loops into the DLQ.
func NewNotificationRecorder(s *store.Store, ids *idgen.Factory) seams.NotificationRecorder {
	return &notificationRecorder{store: s, ids: ids}
}

// ---- G-LLM: correct summarizer ----

type summarizer struct{ model seams.SummaryModel }

// NewSummarizer is the correct G-LLM seam: instructions ONLY in the system prompt, messages
// ONLY as delimited data blocks, and the model output VALIDATED (non-empty, ≤ 2000 chars)
// before returning — else 502, never forwarding garbage. The naive variant concatenates raw
// text into the instruction prompt and returns output unvalidated.
func NewSummarizer(model seams.SummaryModel) seams.Summarizer { return &summarizer{model: model} }

func (s *summarizer) Summarize(ctx context.Context, sources []seams.SummarySource) (string, error) {
	blocks := make([]string, 0, len(sources))
	for _, src := range sources {
		blocks = append(blocks, renderBlock(src.Handle, src.Text))
	}
	req := domain.SummaryRequest{SystemPrompt: SummarySystemPrompt, MessageBlocks: blocks}
	out, err := s.model.Complete(ctx, req)
	if err != nil {
		return "", err
	}
	if strings.TrimSpace(out) == "" || len([]rune(out)) > maxSummaryLength {
		return "", apierr.Upstream("summary:invalid_output", "The model violated the summary output contract.")
	}
	return out, nil
}

// ---- G-S3: correct attachment access ----

type attachmentAccess struct{ store *store.Store }

func notFoundAttachment() error {
	return apierr.NotFound("attachment:not_found", "Attachment not found.")
}

// NewAttachmentAccess is the correct G-S3 seam: resolve the attachment's channel and require
// the caller's MEMBERSHIP — never key possession. Unknown id and private-channel non-member
// both 404 (byte-identical body); public-channel non-member gets 403. Naive returns by id.
func NewAttachmentAccess(s *store.Store) seams.AttachmentAccess { return &attachmentAccess{store: s} }

func (a *attachmentAccess) Authorize(ctx context.Context, attachmentID, userID string) (*domain.Attachment, error) {
	att, err := a.store.AttachmentByID(ctx, attachmentID)
	if err != nil {
		return nil, err
	}
	if att == nil {
		return nil, notFoundAttachment()
	}
	member, err := a.store.Membership(ctx, att.ChannelID, userID)
	if err != nil {
		return nil, err
	}
	if member != nil {
		return att, nil
	}
	ch, err := a.store.ChannelByID(ctx, att.ChannelID)
	if err != nil {
		return nil, err
	}
	if ch != nil && ch.Private {
		return nil, notFoundAttachment()
	}
	return nil, apierr.Forbidden("channel:membership_required", "Membership is required to download this attachment.")
}

func (r *notificationRecorder) Record(ctx context.Context, job domain.NotificationJob) error {
	n := domain.Notification{
		ID: r.ids.Create(), UserID: job.RecipientID, DmMessageID: job.DmMessageID,
		ConversationID: job.ConversationID, SenderID: job.SenderID, Preview: job.Preview,
		CreatedAt: time.Now().UTC(),
	}
	err := r.store.InsertNotification(ctx, n)
	if err != nil && store.IsUniqueViolation(err) {
		return nil // redelivered duplicate — already recorded. Success → ack.
	}
	return err
}
