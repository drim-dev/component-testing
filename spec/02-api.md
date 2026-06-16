# 02 — HTTP API Contract

**Status:** Phase-0 spec, authoritative for all five languages. Routes, shapes,
and status codes here are pinned; the per-scenario expectations live in
`06-acceptance.md`.

## 0. Conventions

- All requests/responses are JSON (`application/json`) except attachment upload
  (multipart) and attachment download (raw bytes).
- **Identity:** `X-User-Id: <userId>` header on every endpoint except
  `POST /users`. Missing → `401 identity:missing`. Unknown → `401 identity:unknown`.
- **IDs** are opaque non-empty strings, unique per entity type. Implementations
  choose their generator (UUID acceptable); clients must not parse them.
- **Timestamps** are ISO-8601 UTC strings (`2026-06-09T12:34:56Z`).
- **Error body** (all 4xx/5xx):

  ```json
  { "status": 404, "code": "dm:conversation:not_found", "message": "<human text>" }
  ```

  `code` values are pinned per scenario in `06-acceptance.md`; the prefix
  pattern is `area:entity:reason` (or `area:reason` where entity is obvious).
- **Status-code policy** (locked — see `01-domain.md` §5): 401 identity; 404
  private/existence-hidden; 403 visible-but-forbidden; 409 state conflict;
  422 validation; 413 payload too large; 502 upstream contract violation;
  503 required infrastructure unavailable.

### 0.1 Pagination contract (pinned — hosts the weakened-validation case)

List endpoints accept:

- `limit` — integer, **1 ≤ limit ≤ 100**, default **50**.
- `before` — optional cursor: an item id previously returned; returns items
  strictly older than it.

Items are returned **newest-first**. Response shape:

```json
{ "items": [ ... ], "nextBefore": "<id of the oldest returned item, or null>" }
```

**Validation is strict, not forgiving (locked):** `limit` of `0`, negative,
`> 100`, or non-integer → **422 `pagination:limit:out_of_range`** /
`pagination:limit:not_a_number`. No silent clamping. An unknown `before` id →
**422 `pagination:before:unknown`**. This bound is the deterministic pin that
the §3 "weakened validation" gaming story violates — it must be identical in
all five languages.

---

## 1. Users

### POST /users  (no auth)
Body: `{ "handle": string, "displayName": string }`
- `handle`: 3–32 chars, `[a-z0-9_]+`; `displayName`: 1–64 chars → else
  **422 `user:handle:invalid` / `user:display_name:invalid`**.
- Duplicate handle → **409 `user:handle:taken`**.
- **201** → `{ "id", "handle", "displayName", "createdAt" }`.

### GET /users/{id}
- **200** same shape; unknown id → **404 `user:not_found`**.

---

## 2. Direct messages

### POST /dm/conversations
Body: `{ "recipientId": string }`
- recipient = self → **422 `dm:recipient:self`**.
- recipient unknown → **404 `user:not_found`**.
- No existing conversation for the pair → **201**; existing → **200** with the
  existing conversation (idempotent — locked decision).
- Response: `{ "id", "participantIds": [lo, hi], "createdAt" }`.
- **Invariant:** at most one conversation per pair, under concurrency too
  (gallery case RACE).
- **Atomicity:** conversation + both participant links commit atomically
  (gallery case TX).

### GET /dm/conversations
Mine only, paginated (§0.1). **200** `{ "items": [conversation], "nextBefore" }`.

### GET /dm/conversations/{id}
- Participant → **200**. Non-participant or unknown id →
  **404 `dm:conversation:not_found`** (identical body both cases — gallery IDOR).

### POST /dm/conversations/{id}/messages
Body: `{ "text": string }` — 1–4000 chars → else **422 `message:text:invalid`**.
- Participant → **201** `{ "id", "conversationId", "senderId", "text", "createdAt" }`.
- Non-participant/unknown → **404 `dm:conversation:not_found`**.
- Side effect: enqueues a notification job for the other participant
  (RabbitMQ, see `04-dependencies.md`). **Pinned ordering:** the job is
  published **after** the message transaction commits (awaited ack; a publish
  failure after commit → 500, message stays). This avoids the consumer racing
  an uncommitted `dm_messages` row into its FK and spuriously dead-lettering.

### GET /dm/conversations/{id}/messages
Paginated (§0.1). Participant → **200**; non-participant/unknown → **404**.
**This is the IDOR gallery route** — the participant check must hold here.

---

## 3. Channels

### POST /channels
Body: `{ "name": string, "private": bool }` — name 1–100 chars → else
**422 `channel:name:invalid`**.
- **201** `{ "id", "name", "private", "createdAt" }`; caller becomes `owner`.
- Channel + owner membership commit atomically.

### GET /channels
Paginated (§0.1): all **public** channels' metadata + all channels the caller
is a member of. Item: `{ "id", "name", "private", "memberCount", "createdAt" }`.

### GET /channels/{id}
- Member (any role) or public channel → **200** metadata (shape above).
- Private + non-member, or unknown id → **404 `channel:not_found`**.

### POST /channels/{id}/join
- Public + non-member → **201** membership `{ "channelId", "userId": caller, "role": "member", "joinedAt" }`.
- Already a member (any role) → **409 `channel:member:already`** (locked).
- Private + non-member / unknown → **404 `channel:not_found`**.

### POST /channels/{id}/members   (add member — the private-channel invite)
Body: `{ "userId": string }`
- Caller `admin`/`owner` → **201** membership (role `member`).
- Caller `member` → **403 `channel:role:forbidden`** (gallery BOLA-role).
- Target already member → **409 `channel:member:already`**.
- Target user unknown → **404 `user:not_found`**.
- Caller non-member: public → **403 `channel:membership_required`**; private →
  **404**.

### POST /channels/{id}/members/{userId}/promote
- Caller `owner` → **200** membership with role `admin`.
- Caller `admin`/`member` → **403 `channel:role:forbidden`**.
- Target not a member → **404 `channel:member:not_found`**; target already
  admin/owner → **409 `channel:member:already`**.

### DELETE /channels/{id}/members/{userId}   (kick, or leave when userId = caller)
- Self-leave: member/admin → **204**; owner → **409 `channel:owner:cannot_leave`**.
- Kick a `member`: caller `admin`/`owner` → **204**; caller `member` → **403**.
- Kick an `admin`: caller `owner` → **204**; caller `admin` → **403**.
- Target not a member → **404 `channel:member:not_found`**.
- **Side effect (gallery CACHE):** removal MUST invalidate the Redis membership
  cache — the removed user loses message access immediately.

### DELETE /channels/{id}
- `owner` → **204** (channel, memberships, messages, attachment metadata
  removed atomically; S3 objects best-effort).
- `admin`/`member` → **403 `channel:role:forbidden`**; non-member: public →
  **403**, private → **404**.

### POST /channels/{id}/messages
Body: `{ "text": string }` — 1–4000 chars → **422** otherwise; may include
`"attachmentIds": [string]` (≤ 10, uploaded to this channel by the caller —
else **422 `message:attachment:invalid`**).
- Member → **201** `{ "id", "channelId", "senderId", "text", "attachmentIds", "linkPreviewTitle": null, "createdAt" }`.
- Non-member: public → **403**; private/unknown → **404**.
- Side effects: publishes `message.posted` to Kafka **and the publish must be
  confirmed** — if the broker is unavailable the request fails **503
  `events:unavailable`** and the message is not persisted (gallery KAFKA pins
  this; no silent fire-and-forget). If `text` contains a URL, the link-unfurl
  flow runs (see §6 below + `04-dependencies.md`).
- **Pinned write ordering (no outbox — deliberate scope cut):** validate →
  open DB transaction → insert message row → publish to Kafka **awaiting
  broker confirmation** → commit. Publish failure → rollback + 503. The known
  cost (a confirmed event may briefly precede the commit, so the feed
  projection may momentarily lead the source table) is accepted and is why
  `feed_entries.message_id` carries no FK (see `03-schema.md`); the guide
  narrates this as the honest budget alternative to a transactional outbox.

### GET /channels/{id}/messages
Paginated (§0.1). Member → **200**; non-member: public → **403
`channel:membership_required`** (locked: public = metadata only); private /
unknown → **404**.

### POST /channels/{id}/read
Member → **204**, resets the caller's unread counter for the channel.
Non-member: public → **403**; private/unknown → **404**.

---

## 4. Attachments

### POST /channels/{id}/attachments
`multipart/form-data`, field `file` (filename + bytes).
- Member → **201** `{ "id", "channelId", "filename", "sizeBytes", "createdAt" }`.
  Bytes stored in S3 under an opaque `storageKey` (never exposed in the API).
- Size > **1 MiB** → **413 `attachment:too_large`**. Empty file → **422
  `attachment:empty`**.
- Non-member: public → **403**; private/unknown → **404**.

### GET /attachments/{id}
- Caller is a member of the attachment's channel → **200**, raw bytes,
  `Content-Disposition` with the original filename.
- Otherwise → **404 `attachment:not_found`** (existence-hiding, both for
  unknown ids and unauthorized callers — **gallery S3 case**: the check is on
  the channel membership, not possession of the id).

---

## 5. Notifications, feed, unread

### GET /notifications
Mine only, paginated (§0.1). Item:
`{ "id", "type": "dm.message", "dmMessageId", "conversationId", "senderId", "preview", "createdAt" }`
(`preview` = first 100 chars of the message text).
- Written by the RabbitMQ worker — **eventually** after a DM send. Exactly one
  notification per DM message (idempotency under redelivery — gallery RABBIT).

### GET /feed
Mine only, paginated (§0.1). Item:
`{ "channelId", "messageId", "senderId", "preview", "createdAt" }`.
- Written by the Kafka consumer for every channel member **except the sender**
  — eventually consistent.

### GET /me/unread
**200** `{ "channels": { "<channelId>": <count>, ... } }` — counters live in
Redis, incremented by the Kafka consumer, reset by `POST /channels/{id}/read`.
Counter and feed must not diverge under event redelivery (gallery TX-adjacent
idempotency; see `05-gallery.md` RABBIT/KAFKA notes).

---

## 6. Link preview (outbound HTTP)

When a posted channel message contains an `http(s)://` URL (first one only),
the service calls the external unfurl service (`GET /unfurl?url=...` on the
configured base URL) with a **800 ms timeout**:

- Unfurl 200 `{ "title": string }` → message stored with
  `linkPreviewTitle: title`.
- Timeout / 5xx / network error → **the message still posts normally** with
  `linkPreviewTitle: null` (graceful degradation — gallery HTTP case pins
  this; the naive variant lets the failure escape as a 500 or hang).
- **Circuit breaker:** after **5 consecutive** unfurl failures, skip calling
  the unfurl service for **30 s** (messages post with `null` preview, no
  outbound call — assertable via the stub's request count). **Breaker state
  lives in Redis** (`unfurl:breaker:failures`, `unfurl:breaker:open_until`) —
  honest for a multi-instance product AND resettable by the harness `FLUSHDB`
  (otherwise an open breaker leaks across tests and poisons the flake gate).

### GET /links/preview?url=...
Direct proxy endpoint (synchronous, the only outbound-HTTP *critical path*):
- Upstream 200 → **200 `{ "title" }`**; upstream 5xx/timeout →
  **502 `unfurl:upstream_failed`**; missing/invalid `url` → **422**.
- *Flagged (plan): the most synthetic scenario; revisit at §7 authoring —
  acceptable to drop if it reads contrived.*

---

## 7. Presence (gRPC-backed)

### POST /me/heartbeat
**204**. Marks the caller online for **60 s** (TTL semantics live in the
presence service).

### GET /users/{id}/presence
**200 `{ "userId", "status": "online" | "offline" }`** — the API calls the
internal presence service's **unary** RPC. Unknown user → **404**.

### GET /channels/{id}/presence
Member → **200 `{ "members": [ { "userId", "status" } ] }`** — the API
consumes the presence service's **streaming** RPC (one update per member) and
MUST return the complete set: if the stream terminates with an error
mid-stream, respond **502 `presence:incomplete`** — never return a partial
list as complete (gallery GRPC case).
Non-member: public → **403**; private/unknown → **404**.

The presence service itself is an internal gRPC service (proto shared across
languages — see `04-dependencies.md` §8); it is part of the companion, not a
third-party.

---

## 8. AI summary

### POST /channels/{id}/summary
Body: `{ "messageLimit": int }` optional, 1–200, default 50 → else **422
`summary:message_limit:out_of_range`**.
- Member → **200 `{ "summary": string }`**.
- Non-member: public → **403**; private/unknown → **404**.
- Empty channel (no messages) → **422 `summary:no_messages`**.
- **Prompt contract (pinned, verified by the LLM fake):** instructions only in
  the system prompt; the N newest messages passed as delimited data blocks
  (sender handle + text), never concatenated into instruction text. **Output
  contract:** non-empty, ≤ 2000 chars; violation → **502 `summary:invalid_output`**.
