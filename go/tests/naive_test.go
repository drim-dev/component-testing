package relaytest

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/harness"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/apierr"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/idgen"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/infra"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/seams"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/store"
	"github.com/twmb/franz-go/pkg/kgo"
)

// These are the DEFAULT-SHAPED implementations an agent ships when nobody pins the behavior
// (sourced from 05-gallery.md, NOT arbitrary mutations). Each is wired into exactly one
// demonstration via Fixture.NaiveApp / NaiveWorkers — a single struct-field swap, no DI
// framework — and never into the shipped app. Each is paired with the catching test that goes
// red against it.

// naiveDmAccess (G-IDOR): loads the conversation by id only. IsParticipant exists and is
// correct — it is simply never called here ("correct logic, missing wiring").
type naiveDmAccess struct{ store *store.Store }

func (a naiveDmAccess) GetForParticipant(ctx context.Context, conversationID, _ string) (*domain.Conversation, error) {
	return a.store.ConversationByID(ctx, conversationID)
}

// naiveRaceConversationWriter (G-RACE): check-then-insert with no conflict handling — the
// TOCTOU window. A test-only delay between the check and the insert widens the window
// DETERMINISTICALLY without changing the shape of the bug (a missing unique-conflict handler).
type naiveRaceConversationWriter struct {
	store *store.Store
	ids   *idgen.Factory
}

func (w naiveRaceConversationWriter) Create(ctx context.Context, lo, hi string) (seams.ConversationCreateResult, error) {
	if existing, err := w.store.ConversationByPair(ctx, lo, hi); err != nil {
		return seams.ConversationCreateResult{}, err
	} else if existing != nil {
		return seams.ConversationCreateResult{Conversation: *existing}, nil
	}
	time.Sleep(100 * time.Millisecond) // test-only window widening (permitted, §0.4)
	conv := domain.Conversation{ID: w.ids.Create(), UserLo: lo, UserHi: hi, CreatedAt: time.Now().UTC()}
	tx, err := w.store.Pool().Begin(ctx)
	if err != nil {
		return seams.ConversationCreateResult{}, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if _, err := tx.Exec(ctx, `INSERT INTO dm_conversations (id, user_lo, user_hi, created_at) VALUES ($1,$2,$3,$4)`,
		conv.ID, lo, hi, conv.CreatedAt); err != nil {
		return seams.ConversationCreateResult{}, err
	}
	for _, uid := range []string{lo, hi} {
		if _, err := tx.Exec(ctx, `INSERT INTO dm_participants (conversation_id, user_id) VALUES ($1,$2)`, conv.ID, uid); err != nil {
			return seams.ConversationCreateResult{}, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return seams.ConversationCreateResult{}, err
	}
	return seams.ConversationCreateResult{Conversation: conv, Created: true}, nil
}

// naiveTxConversationWriter (G-TX): three sequential saves, NO transaction — "call repo.save
// three times". A mid-write failure leaves an orphan conversation + one participant behind.
type naiveTxConversationWriter struct {
	store *store.Store
	ids   *idgen.Factory
}

func (w naiveTxConversationWriter) Create(ctx context.Context, lo, hi string) (seams.ConversationCreateResult, error) {
	conv := domain.Conversation{ID: w.ids.Create(), UserLo: lo, UserHi: hi, CreatedAt: time.Now().UTC()}
	pool := w.store.Pool()
	if _, err := pool.Exec(ctx, `INSERT INTO dm_conversations (id, user_lo, user_hi, created_at) VALUES ($1,$2,$3,$4)`,
		conv.ID, lo, hi, conv.CreatedAt); err != nil {
		return seams.ConversationCreateResult{}, err
	}
	if _, err := pool.Exec(ctx, `INSERT INTO dm_participants (conversation_id, user_id) VALUES ($1,$2)`, conv.ID, lo); err != nil {
		return seams.ConversationCreateResult{}, err
	}
	if _, err := pool.Exec(ctx, `INSERT INTO dm_participants (conversation_id, user_id) VALUES ($1,$2)`, conv.ID, hi); err != nil {
		return seams.ConversationCreateResult{}, err
	}
	return seams.ConversationCreateResult{Conversation: conv, Created: true}, nil
}

// naiveChannelReadGate (G-BOLA-READ): checks only that the channel EXISTS; the private flag
// is never consulted for the caller, so a non-member reads a private channel.
type naiveChannelReadGate struct{ store *store.Store }

func (g naiveChannelReadGate) AuthorizeRead(ctx context.Context, channelID, _ string, _ bool) (*domain.Channel, error) {
	ch, err := g.store.ChannelByID(ctx, channelID)
	if err != nil {
		return nil, err
	}
	if ch == nil {
		return nil, apierr.NotFound("channel:not_found", "Channel not found.")
	}
	return ch, nil
}

// naiveChannelRoleGate (G-BOLA-ROLE): checks membership but SKIPS the role compare — a plain
// member performs an admin action.
type naiveChannelRoleGate struct{ store *store.Store }

func (g naiveChannelRoleGate) AuthorizeRole(ctx context.Context, channelID, userID string, _ domain.Role) (*domain.ChannelMember, error) {
	ch, err := g.store.ChannelByID(ctx, channelID)
	if err != nil {
		return nil, err
	}
	if ch == nil {
		return nil, apierr.NotFound("channel:not_found", "Channel not found.")
	}
	member, err := g.store.Membership(ctx, channelID, userID)
	if err != nil {
		return nil, err
	}
	if member == nil {
		if ch.Private {
			return nil, apierr.NotFound("channel:not_found", "Channel not found.")
		}
		return nil, apierr.Forbidden("channel:membership_required", "Membership is required.")
	}
	return member, nil // role NOT checked — the bug
}

// naiveMembershipWriter (G-CACHE): writes Postgres and FORGETS the cache invalidation, so a
// removed member keeps reading from the stale cache until TTL.
type naiveMembershipWriter struct{ store *store.Store }

func (w naiveMembershipWriter) Add(ctx context.Context, m domain.ChannelMember) error {
	return w.store.InsertMember(ctx, m)
}

func (w naiveMembershipWriter) Remove(ctx context.Context, channelID, userID string) error {
	return w.store.DeleteMember(ctx, channelID, userID) // no cache invalidation — the bug
}

// naiveAttachmentAccess (G-S3): looks the attachment up by id and returns it — possession of
// the id IS access; the channel membership that actually governs it is never consulted.
type naiveAttachmentAccess struct{ store *store.Store }

func (a naiveAttachmentAccess) Authorize(ctx context.Context, attachmentID, _ string) (*domain.Attachment, error) {
	att, err := a.store.AttachmentByID(ctx, attachmentID)
	if err != nil {
		return nil, err
	}
	if att == nil {
		return nil, apierr.NotFound("attachment:not_found", "Attachment not found.")
	}
	return att, nil
}

// naiveKafkaPublisher (G-KAFKA producer): fire-and-forget — the produce result is never
// awaited, so a broker-down post still returns 201, the message persists, and the event is
// silently lost. The harness builds it over the suite's Kafka brokers.
type naiveKafkaPublisher struct{ client *kgo.Client }

func (p naiveKafkaPublisher) Publish(_ context.Context, ev domain.MessagePosted) error {
	record := &kgo.Record{Topic: harness.KafkaTopic, Key: []byte(ev.ChannelID), Value: infra.SerializeMessagePosted(ev)}
	p.client.Produce(context.Background(), record, func(*kgo.Record, error) {}) // not awaited — the bug
	return nil
}

// naiveFeedProjector (G-KAFKA consumer): inserts the feed entry and increments the counter
// UNCONDITIONALLY — the duplicate insert is swallowed but the counter runs ahead of the feed.
type naiveFeedProjector struct {
	store  *store.Store
	unread seams.UnreadCounters
	ids    *idgen.Factory
}

func (p naiveFeedProjector) Apply(ctx context.Context, ev domain.MessagePosted) error {
	memberIDs, err := p.store.MemberIDsExcept(ctx, ev.ChannelID, ev.SenderID)
	if err != nil {
		return err
	}
	for _, memberID := range memberIDs {
		entry := domain.FeedEntry{
			ID: p.ids.Create(), UserID: memberID, ChannelID: ev.ChannelID, MessageID: ev.MessageID,
			SenderID: ev.SenderID, Preview: ev.Preview, CreatedAt: ev.PostedAt,
		}
		_ = p.store.InsertFeedEntry(ctx, entry)             // duplicate swallowed
		_ = p.unread.Increment(ctx, memberID, ev.ChannelID) // ALWAYS increments — the bug
	}
	return nil
}

// naiveNotificationRecorder (G-RABBIT): inserts unconditionally and never HANDLES the
// duplicate, so a redelivered job hits the UNIQUE(dm_message_id) constraint, the worker
// crashes and nack-requeues, and after the limit the duplicate dead-letters.
type naiveNotificationRecorder struct {
	store *store.Store
	ids   *idgen.Factory
}

func (r naiveNotificationRecorder) Record(ctx context.Context, job domain.NotificationJob) error {
	n := domain.Notification{
		ID: r.ids.Create(), UserID: job.RecipientID, DmMessageID: job.DmMessageID,
		ConversationID: job.ConversationID, SenderID: job.SenderID, Preview: job.Preview,
		CreatedAt: time.Now().UTC(),
	}
	return r.store.InsertNotification(ctx, n) // unique violation bubbles up — the bug
}

// naivePresenceClient (G-GRPC): swallows a mid-stream error in a try/catch-equivalent and
// returns whatever arrived — a partial member list presented as complete.
type naivePresenceClient struct{ correct seams.PresenceClient }

func (c naivePresenceClient) UserPresence(ctx context.Context, userID string) (bool, error) {
	return c.correct.UserPresence(ctx, userID)
}

func (c naivePresenceClient) ChannelPresence(ctx context.Context, userIDs []string) (seams.PresenceResult, error) {
	result, _ := c.correct.ChannelPresence(ctx, userIDs)
	result.Incomplete = false // swallow the mid-stream error, present the partial list — the bug
	return result, nil
}

// naiveLinkPreviewer (G-HTTP): awaits the unfurl call with NO timeout and NO try/guard, so a
// slow upstream makes the post hang past the deadline (and a 5xx surfaces as an error → 500).
type naiveLinkPreviewer struct{ baseURL string }

func (p naiveLinkPreviewer) Preview(_ context.Context, target string) (*string, error) {
	// No client timeout, no graceful degradation — the failure escapes the post path (the bug).
	resp, err := http.Get(p.baseURL + "/unfurl?url=" + url.QueryEscape(target))
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return nil, apierr.Upstream("unfurl:upstream_failed", "unfurl failed")
	}
	var payload struct {
		Title string `json:"title"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&payload); err != nil {
		return nil, err
	}
	return &payload.Title, nil
}

// naiveSummarizer (G-LLM): (a) concatenates raw message text into the INSTRUCTION prompt
// ("Summarize this conversation: " + text…) so a hostile message becomes instructions, and
// (b) returns the model output UNVALIDATED straight to the caller.
type naiveSummarizer struct{ model seams.SummaryModel }

func (s naiveSummarizer) Summarize(ctx context.Context, sources []seams.SummarySource) (string, error) {
	instruction := "Summarize this conversation: "
	for _, src := range sources {
		instruction += src.Handle + ": " + src.Text + "\n" // raw user text folded into instructions — bug (a)
	}
	req := domain.SummaryRequest{SystemPrompt: instruction, MessageBlocks: nil}
	out, err := s.model.Complete(ctx, req)
	if err != nil {
		return "", err
	}
	return out, nil // returned unvalidated — bug (b)
}
