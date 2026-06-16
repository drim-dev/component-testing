// S-DM direct messages. The IDOR/RACE/TX scenarios are gallery catching tests;
// their naive red→green demos live in tests/gallery/. Here they run against the
// CORRECT assembled app (green).

import { describe, expect, it } from 'vitest';

import { getFixture } from './fixture-holder.js';
import {
  awaitUntil,
  client,
  dbCount,
  seedConversation,
  seedDmMessage,
  seedUser,
  type ApiUser,
  type Page,
} from './helpers.js';

interface ConversationDto {
  id: string;
  participantIds: string[];
  createdAt: string;
}

async function twoUsers(tag: string): Promise<[ApiUser, ApiUser]> {
  const a = await seedUser(`${tag}_a_${Date.now().toString(36)}`);
  const b = await seedUser(`${tag}_b_${Date.now().toString(36)}`);
  return [a, b];
}

describe('S-DM direct messages', () => {
  it('S-DM-01: A creates conversation with B → 201, participantIds = sorted pair', async () => {
    const [a, b] = await twoUsers('dm01');
    const c = await client(a.id);
    const resp = (await c.post('/dm/conversations', { recipientId: b.id })).expect(201);
    const conv = resp.json<ConversationDto>();
    expect(conv.participantIds).toEqual([a.id, b.id].sort());
  });

  it('S-DM-02: repeat create (A→B, then B→A) → 200 both times, same id (idempotent)', async () => {
    const [a, b] = await twoUsers('dm02');
    const ca = await client(a.id);
    const cb = await client(b.id);
    const first = (await ca.post('/dm/conversations', { recipientId: b.id })).expect(201).json<ConversationDto>();
    const again = (await ca.post('/dm/conversations', { recipientId: b.id })).expect(200).json<ConversationDto>();
    const reverse = (await cb.post('/dm/conversations', { recipientId: a.id })).expect(200).json<ConversationDto>();
    expect(again.id).toBe(first.id);
    expect(reverse.id).toBe(first.id);
  });

  it('S-DM-03: create with self → 422 dm:recipient:self', async () => {
    const a = await seedUser('dm03_a');
    const c = await client(a.id);
    (await c.post('/dm/conversations', { recipientId: a.id })).expect(422).expectCode('dm:recipient:self');
  });

  it('S-DM-04: create with unknown recipient → 404 user:not_found', async () => {
    const a = await seedUser('dm04_a');
    const c = await client(a.id);
    (await c.post('/dm/conversations', { recipientId: 'ghost' })).expect(404).expectCode('user:not_found');
  });

  it('S-DM-05 [G-RACE]: ≥8 concurrent creates for the same pair → exactly one row, all 200/201 same id, no 5xx', async () => {
    const [a, b] = await twoUsers('dm05');
    const c = await client(a.id);
    const results = await Promise.all(
      Array.from({ length: 10 }, () => c.post('/dm/conversations', { recipientId: b.id })),
    );
    for (const r of results) {
      expect([200, 201], `status ${r.status}: ${r.rawBody}`).toContain(r.status);
    }
    const ids = new Set(results.map((r) => r.json<ConversationDto>().id));
    expect(ids.size).toBe(1);
    const [lo, hi] = [a.id, b.id].sort();
    expect(await dbCount('dm_conversations', 'user_lo = $1 AND user_hi = $2', lo, hi)).toBe(1);
  });

  it('S-DM-06 [G-TX]: armed fault on 2nd participant insert → 500, zero conversation AND participant rows', async () => {
    const fx = await getFixture();
    const [a, b] = await twoUsers('dm06');
    await fx.database.armParticipantInsertFault();
    const c = await client(a.id);
    const resp = await c.post('/dm/conversations', { recipientId: b.id });
    expect(resp.status).toBe(500);
    const [lo, hi] = [a.id, b.id].sort();
    expect(await dbCount('dm_conversations', 'user_lo = $1 AND user_hi = $2', lo, hi)).toBe(0);
    expect(await dbCount('dm_participants', 'user_id IN ($1, $2)', a.id, b.id)).toBe(0);
  });

  it('S-DM-07: GET /dm/conversations returns only the caller’s, paginated', async () => {
    const a = await seedUser('dm07_a');
    const b = await seedUser('dm07_b');
    const d = await seedUser('dm07_d');
    await seedConversation(a, b);
    await seedConversation(b, d); // a is not a participant
    const ca = await client(a.id);
    const page = (await ca.get('/dm/conversations')).expect(200).json<Page<ConversationDto>>();
    expect(page.items).toHaveLength(1);
    expect(page.items[0].participantIds).toContain(a.id);
  });

  it('S-DM-08 [G-IDOR]: C reads A–B’s conversation → 404, body byte-identical to unknown-id 404', async () => {
    const a = await seedUser('dm08_a');
    const b = await seedUser('dm08_b');
    const cUser = await seedUser('dm08_c');
    const convId = await seedConversation(a, b);
    const cc = await client(cUser.id);
    const leak = (await cc.get(`/dm/conversations/${convId}`)).expect(404).expectCode('dm:conversation:not_found');
    const unknown = (await cc.get('/dm/conversations/no-such-id')).expect(404);
    expect(leak.rawBody).toBe(unknown.rawBody);
  });

  it('S-DM-09 [G-IDOR]: C reads A–B’s messages → 404; no message data leaks', async () => {
    const a = await seedUser('dm09_a');
    const b = await seedUser('dm09_b');
    const cUser = await seedUser('dm09_c');
    const convId = await seedConversation(a, b);
    await seedDmMessage(a, convId, 'secret');
    const cc = await client(cUser.id);
    const resp = (await cc.get(`/dm/conversations/${convId}/messages`)).expect(404).expectCode('dm:conversation:not_found');
    expect(resp.rawBody).not.toContain('secret');
  });

  it('S-DM-10 [G-IDOR]: C posts to A–B’s conversation → 404; no row written', async () => {
    const a = await seedUser('dm10_a');
    const b = await seedUser('dm10_b');
    const cUser = await seedUser('dm10_c');
    const convId = await seedConversation(a, b);
    const cc = await client(cUser.id);
    (await cc.post(`/dm/conversations/${convId}/messages`, { text: 'intrude' }))
      .expect(404)
      .expectCode('dm:conversation:not_found');
    expect(await dbCount('dm_messages', 'conversation_id = $1', convId)).toBe(0);
  });

  it('S-DM-11: A sends 3 messages → 201 each; A and B list newest-first (G-TAUT honest counterpart)', async () => {
    const a = await seedUser('dm11_a');
    const b = await seedUser('dm11_b');
    const convId = await seedConversation(a, b);
    const ca = await client(a.id);
    for (const t of ['one', 'two', 'three']) {
      (await ca.post(`/dm/conversations/${convId}/messages`, { text: t })).expect(201);
    }
    for (const u of [a, b]) {
      const cu = await client(u.id);
      const page = (await cu.get(`/dm/conversations/${convId}/messages`)).expect(200).json<Page<{ text: string; senderId: string }>>();
      expect(page.items.map((m) => m.text)).toEqual(['three', 'two', 'one']);
      expect(page.items.every((m) => m.senderId === a.id)).toBe(true);
    }
    // The notification fan-out reaches B (proves the message path is wired end-to-end).
    const cb = await client(b.id);
    await awaitUntil(
      async () => (await cb.get('/notifications')).json<Page<unknown>>().items.length,
      (n) => n === 3,
      { label: 'S-DM-11 notifications' },
    );
  });

  it('S-DM-12: message text empty / 4001 chars → 422 message:text:invalid', async () => {
    const a = await seedUser('dm12_a');
    const b = await seedUser('dm12_b');
    const convId = await seedConversation(a, b);
    const ca = await client(a.id);
    (await ca.post(`/dm/conversations/${convId}/messages`, { text: '' })).expect(422).expectCode('message:text:invalid');
    (await ca.post(`/dm/conversations/${convId}/messages`, { text: 'x'.repeat(4001) }))
      .expect(422)
      .expectCode('message:text:invalid');
  });
});
