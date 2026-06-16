# 01 — Domain & Behavior Spec

**Product:** Relay — a minimal messaging + community service with an AI assistant.
**Status:** Phase-0 spec. This file is part of the single source of truth for all
five language implementations. If an implementation disagrees with this spec,
that is a spec bug: fix the spec first, then the code.

> Design source: `docs/designs/2026-06-09-verification-map-guide.md` §11.A.
> Binding decisions: `docs/plans/2026-06-09-verification-map-guide.md` →
> "Decisions & open questions" (404/403 policy, idempotent DM create, trusted
> header identity, pagination 422, lying-test naming).

---

## 1. What Relay is

Relay is a deliberately small messaging + community product:

- **Direct messages (DMs)** — 1-to-1 private conversations.
- **Channels** — community spaces with membership and roles, public or private.
- **AI assistant** — channel summaries only (one LLM surface).

Relay exists to host the guide's bug gallery in *natural* locations: the code is
organized like a real product (`src/` by domain), the bugs' naive variants are
ordinary-looking code, and the tests are the lens that reveals them.

## 2. What Relay is explicitly NOT (YAGNI list)

Relay is **not a Telegram clone**. The following are deliberately out of scope
and MUST NOT be implemented in any language:

- voice / video calls
- reactions, stickers, emoji pickers
- bot platform / integrations
- message editing or edit history
- message deletion
- search UI / full-text search
- read receipts per message (only channel-level unread counters)
- typing indicators
- smart replies (AI surface is **summary only** — decided §11.J)
- user blocking / moderation queues
- any frontend — Relay is an HTTP API only

## 3. Identity model

- No OAuth, no passwords, no sessions. The caller's identity is the trusted
  **`X-User-Id`** request header (decided, locked). This mirrors the
  BFF-forwards-identity pattern and keeps the companion focused on
  authorization, not authentication.
- Missing `X-User-Id` → **401**. `X-User-Id` that does not match an existing
  user → **401**.
- `POST /users` (bootstrap/registration) is the only endpoint callable without
  `X-User-Id`.

## 4. The two first-class features (kept separate)

DMs and channels are **separate models with separate authorization surfaces**
(decided §11.A — no unified "membership" abstraction):

### 4.1 Direct messages

- A DM **conversation** is an unordered pair of two distinct users.
- At most **one** conversation exists per pair (uniqueness is a system
  invariant).
- **Creating a conversation is idempotent**: if a conversation for the pair
  already exists, return it with **200** (not 409, not a duplicate). First
  creation returns **201**. (Locked decision.)
- **Access rule:** only the two participants can see the conversation, read its
  messages, or send into it. For anyone else the conversation **does not
  exist** → **404** (existence-hiding).
- A user cannot open a conversation with themselves → **422**.

### 4.2 Channels

- A channel has a name, a `private` flag, and members with **roles**:
  `owner` > `admin` > `member`.
- The creator becomes the single **owner** member. Exactly one owner per
  channel (no ownership transfer — YAGNI).
- **Public channel:** metadata (name, id, member count) is discoverable by any
  authenticated user; anyone may join themselves. **Reading or posting messages
  still requires membership** (locked decision: public = metadata-discoverable
  only).
- **Private channel:** invisible to non-members. Every request from a
  non-member — metadata, messages, join, anything addressed to it — returns
  **404** (existence-hiding). Members are added only by `admin`/`owner`.

### 4.3 Role matrix (channels)

| Action | member | admin | owner | non-member (public) | non-member (private) |
|---|---|---|---|---|---|
| read metadata | ✓ | ✓ | ✓ | ✓ | 404 |
| join self (public) | 409 already | 409 | 409 | ✓ | — |
| read messages | ✓ | ✓ | ✓ | 403 | 404 |
| post message | ✓ | ✓ | ✓ | 403 | 404 |
| upload/fetch attachment | ✓ | ✓ | ✓ | 403 | 404 |
| add member (private ch.) | 403 | ✓ | ✓ | — | 404 |
| remove (kick) member | 403 | ✓ | ✓ | 403 | 404 |
| kick an admin | 403 | 403 | ✓ | — | 404 |
| promote member → admin | 403 | 403 | ✓ | — | 404 |
| leave (remove self) | ✓ | ✓ | 409 (owner cannot leave) | — | 404 |
| delete channel | 403 | 403 | ✓ | 403 | 404 |
| request summary | ✓ | ✓ | ✓ | 403 | 404 |
| read channel presence | ✓ | ✓ | ✓ | 403 | 404 |

## 5. The 404/403 policy (locked, system-wide)

- **404** for unauthorized access to **private** resources (private channels,
  DM conversations, attachments of private channels): their existence is
  hidden. The response body is identical to a true not-found.
- **403** for **visible-but-forbidden** actions: the caller can legitimately
  see the resource (public channel metadata; a channel they are a member of)
  but lacks membership/role for the action.
- **401** strictly for identity failures (missing/unknown `X-User-Id`).

Rationale lives in the guide (§4 gallery): a correct 404/403 split is exactly
the kind of behavior a mock-based test never pins.

## 6. AI assistant surface (summary only)

- One endpoint: summarize the recent messages of a channel
  (`POST /channels/{id}/summary`). Members only (role ≥ member).
- **Trust boundary (load-bearing for the LLM gallery case):** user message
  content is untrusted input crossing into the prompt. The correct
  implementation MUST:
  1. keep instructions in the **system prompt** and pass message content as
     clearly **delimited data** (never concatenated into the instruction text);
  2. **validate model output** before returning it: non-empty, ≤ 2000
     characters; otherwise respond **502** (the model violated its contract —
     never forward unvalidated output).
- The LLM is the canonical **fake** dependency (see `04-dependencies.md`): the
  fake verifies the *interaction* (prompt structure, delimiting) — this is
  where "real vs fake boundary" is taught.
- DM-thread summary: YAGNI (second surface, no new teaching value — §11.J).

## 7. Entities (canonical list)

| Entity | Key fields | Ownership / authorization anchor |
|---|---|---|
| User | id, handle (unique), displayName | self |
| DmConversation | id, userLo, userHi (normalized pair, unique) | the two participants |
| DmMessage | id, conversationId, senderId, text | via conversation participants |
| Channel | id, name, private | via membership role |
| ChannelMember | channelId, userId, role | admin/owner manage; self may leave |
| ChannelMessage | id, channelId, senderId, text, linkPreviewTitle? | via membership |
| Attachment | id, channelId, uploaderId, messageId?, filename, sizeBytes, storageKey | via channel membership |
| Notification | id, userId, dmMessageId (unique — idempotency anchor), readFlag | recipient only |
| FeedEntry | userId, channelId, messageId (unique per user+message) | recipient only |

Authorization predicates the implementations must define (and which the gallery
shows being "correct but not wired" in naive variants):

- `isParticipant(user, conversation)` — DM access.
- `isMember(user, channel)` / `hasRole(user, channel, atLeast)` — channel access.
- Attachment access resolves to `isMember(user, attachment.channel)`.

## 8. Dependency roles in the product (from design §11.A, frozen)

| Dependency | Organic role in Relay |
|---|---|
| PostgreSQL | users, conversations, messages, channels, memberships, attachments metadata, notifications, feed |
| Redis | channel-membership cache (authorization fast path), per-channel unread counters |
| Kafka | `message.posted` event stream → fan-out to members' feeds + unread counters |
| RabbitMQ | DM notification-delivery jobs (acks, retry, DLQ) |
| S3 (MinIO) | attachment bytes |
| LLM | the channel-summary assistant — the canonical fake |
| HTTP (outbound) | link-unfurl service for URL previews (stub + fault injection) |
| gRPC (thin) | internal presence service (one unary + one streaming RPC) |

Behavioral contracts per dependency: `04-dependencies.md`. API shapes:
`02-api.md`. Schema: `03-schema.md`. Bug gallery: `05-gallery.md`. The
authoritative scenario catalog: `06-acceptance.md`.
