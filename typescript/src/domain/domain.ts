// Relay's domain entities and the pure predicates the gallery's honesty notes
// call out as the legitimate home of unit tests (participant check, role
// ordering, preview truncation, first-URL). Whether a route WIRES these in is a
// system property the component tests verify; that the predicates are correct
// is unit territory.

// Role is a channel membership role, ordered owner > admin > member.
export enum Role {
  Member = 0,
  Admin = 1,
  Owner = 2,
}

// atLeast reports whether r is at least as privileged as min — the pure
// ordering predicate the G-BOLA-ROLE honesty note unit-tests.
export function atLeast(r: Role, min: Role): boolean {
  return r >= min;
}

export function roleToString(r: Role): string {
  switch (r) {
    case Role.Owner:
      return 'owner';
    case Role.Admin:
      return 'admin';
    default:
      return 'member';
  }
}

export function parseRole(s: string): Role {
  switch (s) {
    case 'owner':
      return Role.Owner;
    case 'admin':
      return Role.Admin;
    default:
      return Role.Member;
  }
}

export interface User {
  id: string;
  handle: string;
  displayName: string;
  createdAt: Date;
}

export interface Conversation {
  id: string;
  userLo: string;
  userHi: string;
  createdAt: Date;
}

// isParticipant is the DM access predicate — pure logic, the G-IDOR honesty
// note's unit target. A read path that never calls it is the bug, not this.
export function isParticipant(c: Conversation, userId: string): boolean {
  return c.userLo === userId || c.userHi === userId;
}

// normalizePair returns the two ids in lexicographic order (lo, hi).
export function normalizePair(a: string, b: string): [string, string] {
  return a < b ? [a, b] : [b, a];
}

export interface DmMessage {
  id: string;
  conversationId: string;
  senderId: string;
  text: string;
  createdAt: Date;
}

export interface Channel {
  id: string;
  name: string;
  private: boolean;
  createdAt: Date;
}

export interface ChannelMember {
  channelId: string;
  userId: string;
  role: Role;
  joinedAt: Date;
}

export interface ChannelMessage {
  id: string;
  channelId: string;
  senderId: string;
  text: string;
  linkPreviewTitle: string | null;
  createdAt: Date;
}

export interface Attachment {
  id: string;
  channelId: string;
  uploaderId: string;
  messageId: string | null;
  filename: string;
  sizeBytes: number;
  storageKey: string;
  createdAt: Date;
}

export interface Notification {
  id: string;
  userId: string;
  dmMessageId: string;
  conversationId: string;
  senderId: string;
  preview: string;
  createdAt: Date;
}

export interface FeedEntry {
  id: string;
  userId: string;
  channelId: string;
  messageId: string;
  senderId: string;
  preview: string;
  createdAt: Date;
}

export const PREVIEW_MAX_LENGTH = 100;

// preview truncates to the first 100 code points — the pure function the
// gallery honesty notes unit-test; the component tests only assert it is wired
// into the event/notification paths.
export function preview(text: string): string {
  const points = [...text];
  return points.length <= PREVIEW_MAX_LENGTH ? text : points.slice(0, PREVIEW_MAX_LENGTH).join('');
}

// firstUrl returns the first http(s):// token in text, or '' — the trigger for
// link unfurl.
export function firstUrl(text: string): string {
  for (const field of text.split(/\s+/)) {
    if (field.startsWith('http://') || field.startsWith('https://')) {
      return field;
    }
  }
  return '';
}

// ---- broker / port DTOs ----

// MessagePosted is the Kafka event (topic message-posted, key = channelId)
// fanned out to members' feeds + unread counters.
export interface MessagePosted {
  messageId: string;
  channelId: string;
  senderId: string;
  preview: string;
  postedAt: string;
}

// NotificationJob is the RabbitMQ job (queue notify.dm) the worker turns into a
// notification row, exactly once per DM message under at-least-once redelivery.
export interface NotificationJob {
  dmMessageId: string;
  conversationId: string;
  senderId: string;
  recipientId: string;
  preview: string;
}

// PresenceStatus is one member's presence (from the gRPC stream / unary RPC).
export interface PresenceStatus {
  userId: string;
  online: boolean;
}

// SummaryRequest is what the app hands the SummaryModel: a constant system
// prompt plus the messages as already-rendered, delimited DATA blocks. The fake
// verifies the system prompt equals the pinned constant and that hostile text
// appears ONLY inside a block (G-LLM).
export interface SummaryRequest {
  systemPrompt: string;
  messageBlocks: string[];
}
