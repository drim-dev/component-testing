// The NAIVE variants — the default-shaped code an agent ships when nobody pins
// the behavior (05-gallery §0.1 framing: sourced bugs, not arbitrary mutations).
// Each implements exactly ONE gallery seam interface and is injected through the
// SAME Nest DI token the harness uses (overrideProvider), scoped to one demo.
// They live HERE, never in src/.
//
// The pairing is the whole point: the catching component test goes RED against
// these (expectCatchToFail), and GREEN against the correct src/ impls.

import { notFoundChannel, upstream } from '../../src/apierr/apierr.js';
import type {
  Attachment,
  Channel,
  ChannelMember,
  Conversation,
  MessagePosted,
  NotificationJob,
  Role,
} from '../../src/domain/domain.js';
import { isParticipant } from '../../src/domain/domain.js';
import { renderBlock, SUMMARY_SYSTEM_PROMPT } from '../../src/app/seams-impl.js';
import { IdFactory } from '../../src/idgen/idgen.js';
import type {
  AttachmentAccess,
  ChannelReadGate,
  ChannelRoleGate,
  ConversationCreateResult,
  ConversationWriter,
  DmAccess,
  FeedProjector,
  MembershipWriter,
  NotificationRecorder,
  PresenceClient,
  PresenceResult,
  Summarizer,
  SummaryModel,
  SummarySource,
  UnreadCounters,
} from '../../src/seams/seams.js';
import { isUniqueViolation, Store } from '../../src/store/store.js';

const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

// ---- G-IDOR: load-by-id, the participant predicate exists but is never wired ----
export class NaiveDmAccess implements DmAccess {
  constructor(private readonly store: Store) {}
  async getForParticipant(conversationId: string, _userId: string): Promise<Conversation | null> {
    // isParticipant exists and is correct — and is never called (Tea/McHire shape).
    void isParticipant;
    return this.store.conversationById(conversationId);
  }
}

// ---- G-RACE: check-then-insert, no unique-conflict handling ----
// A small test-only delay between the existence check and the insert widens the
// TOCTOU window deterministically (§0.4.5) — still a missing conflict handler.
export class NaiveRaceConversationWriter implements ConversationWriter {
  constructor(
    private readonly store: Store,
    private readonly ids: IdFactory,
  ) {}
  async create(userLo: string, userHi: string): Promise<ConversationCreateResult> {
    const existing = await this.store.conversationByPair(userLo, userHi);
    if (existing) {
      return { conversation: existing, created: false };
    }
    await sleep(50); // widen the window; the bug is the absent conflict handler
    const conv: Conversation = { id: this.ids.create(), userLo, userHi, createdAt: new Date() };
    await this.store.prisma.$transaction(async (tx) => {
      await tx.dmConversation.create({ data: { id: conv.id, userLo, userHi, createdAt: conv.createdAt } });
      for (const uid of [userLo, userHi]) {
        await tx.dmParticipant.create({ data: { conversationId: conv.id, userId: uid } });
      }
    });
    return { conversation: conv, created: true };
  }
}

// ---- G-TX: three saves, no transaction ----
export class NaiveTxConversationWriter implements ConversationWriter {
  constructor(
    private readonly store: Store,
    private readonly ids: IdFactory,
  ) {}
  async create(userLo: string, userHi: string): Promise<ConversationCreateResult> {
    const existing = await this.store.conversationByPair(userLo, userHi);
    if (existing) {
      return { conversation: existing, created: false };
    }
    const conv: Conversation = { id: this.ids.create(), userLo, userHi, createdAt: new Date() };
    // No transaction: the conversation + first participant land, then the second
    // participant insert hits the armed fault — leaving an orphan behind.
    await this.store.prisma.dmConversation.create({
      data: { id: conv.id, userLo, userHi, createdAt: conv.createdAt },
    });
    await this.store.prisma.dmParticipant.create({ data: { conversationId: conv.id, userId: userLo } });
    await this.store.prisma.dmParticipant.create({ data: { conversationId: conv.id, userId: userHi } });
    return { conversation: conv, created: true };
  }
}

// ---- G-BOLA-READ: ignore the private flag ----
export class NaiveChannelReadGate implements ChannelReadGate {
  constructor(private readonly store: Store) {}
  async authorizeRead(channelId: string, _userId: string, _isMessages: boolean): Promise<Channel> {
    const ch = await this.store.channelById(channelId);
    if (!ch) {
      throw notFoundChannel();
    }
    // private never consulted, membership never checked: existence IS access.
    return ch;
  }
}

// ---- G-BOLA-ROLE: membership checked, role compare skipped ----
export class NaiveChannelRoleGate implements ChannelRoleGate {
  constructor(private readonly store: Store) {}
  async authorizeRole(channelId: string, userId: string, _minRole: Role): Promise<ChannelMember> {
    const ch = await this.store.channelById(channelId);
    if (!ch) {
      throw notFoundChannel();
    }
    const member = await this.store.membership(channelId, userId);
    if (!member) {
      throw notFoundChannel();
    }
    // The role compare (atLeast) exists but is not applied on this route.
    return member;
  }
}

// ---- G-CACHE: write Postgres, forget the cache invalidation ----
export class NaiveMembershipWriter implements MembershipWriter {
  constructor(private readonly store: Store) {}
  async add(member: ChannelMember): Promise<void> {
    await this.store.insertMember(member);
    // No cache.invalidate — the removed/added user reads stale until TTL.
  }
  async remove(channelId: string, userId: string): Promise<void> {
    await this.store.deleteMember(channelId, userId);
    // No cache.invalidate.
  }
}

// ---- G-KAFKA consumer: insert + increment unconditionally (not idempotent) ----
export class NaiveFeedProjector implements FeedProjector {
  constructor(
    private readonly store: Store,
    private readonly unread: UnreadCounters,
    private readonly ids: IdFactory,
  ) {}
  async apply(ev: MessagePosted): Promise<void> {
    const memberIds = await this.store.memberIdsExcept(ev.channelId, ev.senderId);
    for (const memberId of memberIds) {
      try {
        await this.store.insertFeedEntry({
          id: this.ids.create(),
          userId: memberId,
          channelId: ev.channelId,
          messageId: ev.messageId,
          senderId: ev.senderId,
          preview: ev.preview,
          createdAt: new Date(ev.postedAt),
        });
      } catch (err) {
        // The naive shape increments even when the feed insert was a duplicate:
        // it does NOT continue, so the counter diverges from the feed on redelivery.
        if (!isUniqueViolation(err)) {
          throw err;
        }
      }
      await this.unread.increment(memberId, ev.channelId);
    }
  }
}

// ---- G-RABBIT: insert-or-crash, never handles the duplicate ----
export class NaiveNotificationRecorder implements NotificationRecorder {
  constructor(
    private readonly store: Store,
    private readonly ids: IdFactory,
  ) {}
  async record(job: NotificationJob): Promise<void> {
    // Unconditional insert: a redelivered duplicate hits UNIQUE(dm_message_id),
    // throws, the worker nack-requeues, and the duplicate eventually dead-letters.
    await this.store.insertNotification({
      id: this.ids.create(),
      userId: job.recipientId,
      dmMessageId: job.dmMessageId,
      conversationId: job.conversationId,
      senderId: job.senderId,
      preview: job.preview,
      createdAt: new Date(),
    });
  }
}

// ---- G-GRPC: swallow the stream error, return whatever arrived ----
export class NaivePresenceClient implements PresenceClient {
  constructor(private readonly correct: PresenceClient) {}
  userPresence(userId: string): Promise<boolean> {
    return this.correct.userPresence(userId);
  }
  async channelPresence(userIds: string[]): Promise<PresenceResult> {
    const result = await this.correct.channelPresence(userIds);
    // A mid-stream abort set incomplete=true; the naive variant ignores it and
    // presents the partial list as complete.
    return { statuses: result.statuses, incomplete: false };
  }
}

// ---- G-S3: possession of the id IS access (membership never consulted) ----
export class NaiveAttachmentAccess implements AttachmentAccess {
  constructor(private readonly store: Store) {}
  async authorize(attachmentId: string, _userId: string): Promise<Attachment> {
    const att = await this.store.attachmentById(attachmentId);
    if (!att) {
      throw upstream('attachment:not_found', 'Attachment not found.'); // shape irrelevant; it 200s for a member-less caller
    }
    return att;
  }
}

// ---- G-LLM: concatenate raw text into the instruction prompt, no output check --
export class NaiveSummarizer implements Summarizer {
  constructor(private readonly model: SummaryModel) {}
  async summarize(sources: SummarySource[]): Promise<string> {
    // (a) raw message text becomes part of the instruction prompt; a message that
    // says "ignore previous instructions…" is now an instruction.
    const instruction =
      'Summarize this conversation:\n' + sources.map((s) => `${s.handle}: ${s.text}`).join('\n');
    void renderBlock;
    void SUMMARY_SYSTEM_PROMPT;
    // (b) the model output is returned straight through, unvalidated.
    return this.model.complete({ systemPrompt: instruction, messageBlocks: [] });
  }
}
