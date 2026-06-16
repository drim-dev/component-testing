// seams declares the narrow interfaces each gallery case hides behind
// (05-gallery §0.4). The app's handlers depend on these interfaces through Nest
// DI tokens, never on concrete classes. The CORRECT implementations live in
// src/ and are registered in the module; the NAIVE variants live in tests/ and
// are injected through the SAME Nest DI seam — `overrideProvider(TOKEN)` swaps
// exactly one provider for one demonstration, no other change. That a 404/403
// decision is a property of the assembled route (not of a mock) is exactly what
// makes the catching tests catch and the lying tests lie.

import type {
  Attachment,
  Channel,
  ChannelMember,
  Conversation,
  MessagePosted,
  NotificationJob,
  PresenceStatus,
  Role,
  SummaryRequest,
} from '../domain/domain.js';

// ConversationCreateResult carries the conversation and whether this call
// created it (201) or found an existing one (200, idempotent).
export interface ConversationCreateResult {
  conversation: Conversation;
  created: boolean;
}

// DmAccess is the G-IDOR seam: participant-scoped conversation read. Returns
// null when the caller is not a participant (or the conversation is absent), and
// the route 404s — hiding existence. The naive variant loads by id only.
export interface DmAccess {
  getForParticipant(conversationId: string, userId: string): Promise<Conversation | null>;
}
export const DM_ACCESS = Symbol('DmAccess');

// ConversationWriter is the G-RACE / G-TX seam: a transactional,
// unique-conflict-handling create. Concurrent creates for one pair resolve to a
// single row (RACE); a mid-write failure leaves nothing behind (TX). The naive
// variants are check-then-insert (RACE) and three saves with no transaction (TX).
export interface ConversationWriter {
  create(userLo: string, userHi: string): Promise<ConversationCreateResult>;
}
export const CONVERSATION_WRITER = Symbol('ConversationWriter');

// ChannelReadGate is the G-BOLA-READ seam: the 404/403 visibility split for
// reading a channel's metadata/messages. isMessages distinguishes metadata
// (public non-member allowed) from messages (public non-member → 403). Throws an
// ApiError when access is denied; returns the channel otherwise. The naive
// variant ignores the private flag.
export interface ChannelReadGate {
  authorizeRead(channelId: string, userId: string, isMessages: boolean): Promise<Channel>;
}
export const CHANNEL_READ_GATE = Symbol('ChannelReadGate');

// ChannelRoleGate is the G-BOLA-ROLE seam: membership AND role check for admin
// actions. Returns the caller's membership so the handler can apply finer rules
// (e.g. kicking an admin); throws on denial. The naive variant checks membership
// but skips the role compare.
export interface ChannelRoleGate {
  authorizeRole(channelId: string, userId: string, minRole: Role): Promise<ChannelMember>;
}
export const CHANNEL_ROLE_GATE = Symbol('ChannelRoleGate');

// MembershipWriter is the G-CACHE seam: a membership write coupled to cache
// invalidation. The naive variant writes Postgres and forgets to invalidate the
// Redis membership cache.
export interface MembershipWriter {
  add(member: ChannelMember): Promise<void>;
  remove(channelId: string, userId: string): Promise<void>;
}
export const MEMBERSHIP_WRITER = Symbol('MembershipWriter');

// MessagePostedPublisher is the G-KAFKA producer seam: publish awaiting broker
// confirmation (broker down → throw → 503, message not persisted). The naive
// variant fires and forgets.
export interface MessagePostedPublisher {
  publish(ev: MessagePosted): Promise<void>;
}
export const MESSAGE_POSTED_PUBLISHER = Symbol('MessagePostedPublisher');

// FeedProjector is the G-KAFKA consumer seam: idempotent feed insert +
// increment-on-first-insert. The naive variant inserts and increments
// unconditionally.
export interface FeedProjector {
  apply(ev: MessagePosted): Promise<void>;
}
export const FEED_PROJECTOR = Symbol('FeedProjector');

// NotificationRecorder is the G-RABBIT seam: insert treating a duplicate (unique
// violation) as success so the worker acks. The naive variant never handles the
// duplicate and crashes.
export interface NotificationRecorder {
  record(job: NotificationJob): Promise<void>;
}
export const NOTIFICATION_RECORDER = Symbol('NotificationRecorder');

// PresenceResult is the channel-presence outcome: the per-member statuses, or
// incomplete when the stream errored mid-way (→ 502, never a partial list).
export interface PresenceResult {
  statuses: PresenceStatus[];
  incomplete: boolean;
}

// PresenceClient is the G-GRPC seam: consume the presence stream to clean end; a
// mid-stream error sets incomplete. The naive variant swallows the error and
// returns what arrived.
export interface PresenceClient {
  userPresence(userId: string): Promise<boolean>;
  channelPresence(userIds: string[]): Promise<PresenceResult>;
}
export const PRESENCE_CLIENT = Symbol('PresenceClient');

// Heartbeats marks a user online (TTL 60 s) by writing the SAME Redis key the
// presence gRPC service reads, so a heartbeat is observable through both paths.
export interface Heartbeats {
  mark(userId: string): Promise<void>;
}
export const HEARTBEATS = Symbol('Heartbeats');

// LinkPreviewer is the G-HTTP seam: fetch a link title with timeout + circuit
// breaker; failure degrades to no title (never escapes). The naive variant has
// no timeout/guard.
export interface LinkPreviewer {
  preview(url: string): Promise<string | null>;
}
export const LINK_PREVIEWER = Symbol('LinkPreviewer');

// SummaryModel is the LLM port (the canonical FAKE): the app never builds a
// prompt string inline — everything crosses this port. The fake verifies the
// interaction (the captured request). A real deployment registers an
// HTTP-backed model here.
export interface SummaryModel {
  complete(req: SummaryRequest): Promise<string>;
}
export const SUMMARY_MODEL = Symbol('SummaryModel');

// SummarySource is one channel message handed to the Summarizer.
export interface SummarySource {
  handle: string;
  text: string;
}

// Summarizer is the G-LLM seam: it assembles the model request and VALIDATES the
// output. The correct implementation keeps instructions and user content
// separated (prompt injection) and rejects contract-violating output with 502
// (never forwards it). The naive variant concatenates raw message text into the
// instruction prompt and returns output unvalidated.
export interface Summarizer {
  summarize(sources: SummarySource[]): Promise<string>;
}
export const SUMMARIZER = Symbol('Summarizer');

// AttachmentAccess is the G-S3 seam: download authorization derives from the
// attachment's CHANNEL MEMBERSHIP, never from possession of the id or storage
// key. Unknown id and private-channel non-member return the same
// existence-hiding 404; public non-member → 403. The naive variant looks the
// attachment up by id and returns it (possession IS access).
export interface AttachmentAccess {
  authorize(attachmentId: string, userId: string): Promise<Attachment>;
}
export const ATTACHMENT_ACCESS = Symbol('AttachmentAccess');

// AttachmentStore is the object-store port (S3). Bytes live behind an opaque
// storage key; authorization NEVER reads key possession (G-S3).
export interface AttachmentStore {
  put(key: string, data: Buffer): Promise<void>;
  get(key: string): Promise<Buffer>;
  deleteAll(): Promise<void>;
}
export const ATTACHMENT_STORE = Symbol('AttachmentStore');

// NotificationJobs publishes a DM notification job (RabbitMQ) after the message
// commits, awaiting the broker's publisher confirmation.
export interface NotificationJobs {
  enqueue(job: NotificationJob): Promise<void>;
}
export const NOTIFICATION_JOBS = Symbol('NotificationJobs');

// MembershipCache is the Redis authorization fast-path + its invalidation hook.
export interface MembershipCache {
  isMember(channelId: string, userId: string): Promise<{ cached: boolean; member: boolean }>;
  remember(channelId: string, memberIds: string[]): Promise<void>;
  invalidate(channelId: string): Promise<void>;
}
export const MEMBERSHIP_CACHE = Symbol('MembershipCache');

// UnreadCounters is the Redis per-channel unread counter.
export interface UnreadCounters {
  increment(userId: string, channelId: string): Promise<void>;
  reset(userId: string, channelId: string): Promise<void>;
  forUser(userId: string): Promise<Record<string, number>>;
}
export const UNREAD_COUNTERS = Symbol('UnreadCounters');
