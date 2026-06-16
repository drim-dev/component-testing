// S-FD feed / unread / Kafka. The broker-down and redelivery/cache scenarios
// (S-FD-01,05,06) are G-KAFKA/G-CACHE gallery catches; their naive demos live in
// tests/gallery/. S-FD-02 is the await-shape exhibit (the fan-out is eventually
// consistent — never a fabricated instant). The broker is paused/unpaused by the
// harness; the suite restarts it before moving on (zero-flake invariant).

import { describe, expect, it } from 'vitest';

import { getFixture } from './fixture-holder.js';
import { KAFKA_GROUP, KAFKA_TOPIC } from '../harness/kafka.harness.js';
import {
  awaitUntil,
  client,
  dbCount,
  seedChannel,
  seedMember,
  seedUser,
  type ApiUser,
  type Page,
} from './helpers.js';

interface FeedEntryDto {
  channelId: string;
  messageId: string;
  senderId: string;
  preview: string;
}
interface UnreadDto {
  channels: Record<string, number>;
}

let seq = 0;
async function u(tag: string): Promise<ApiUser> {
  return seedUser(`fd_${tag}_${(seq++).toString(36)}_${Date.now().toString(36)}`);
}

async function feedCount(userId: string, channelId: string): Promise<number> {
  const c = await client(userId);
  const page = (await c.get('/feed')).json<Page<FeedEntryDto>>();
  return page.items.filter((f) => f.channelId === channelId).length;
}

async function unreadFor(userId: string, channelId: string): Promise<number> {
  const c = await client(userId);
  const dto = (await c.get('/me/unread')).json<UnreadDto>();
  return dto.channels[channelId] ?? 0;
}

describe('S-FD feed', () => {
  it('S-FD-01 [G-KAFKA]: broker down → post 503 events:unavailable; no channel_messages row', async () => {
    const fx = await getFixture();
    const owner = await u('f01');
    const id = await seedChannel(owner, 'f01', true);
    const c = await client(owner.id);
    await fx.kafka.stopBroker();
    try {
      (await c.post(`/channels/${id}/messages`, { text: 'lost?' })).expect(503).expectCode('events:unavailable');
      expect(await dbCount('channel_messages', 'channel_id = $1', id)).toBe(0);
    } finally {
      await fx.kafka.startBroker();
    }
  });

  it('S-FD-02 [G-KAFKA]: A posts → await feed entries for B and C with preview; none for A', async () => {
    const owner = await u('f02a');
    const b = await u('f02b');
    const cUser = await u('f02c');
    const id = await seedChannel(owner, 'f02', true);
    await seedMember(owner, id, b);
    await seedMember(owner, id, cUser);
    const c = await client(owner.id);
    (await c.post(`/channels/${id}/messages`, { text: 'fan out' })).expect(201);

    for (const member of [b, cUser]) {
      await awaitUntil(() => feedCount(member.id, id), (n) => n === 1, { label: `S-FD-02 ${member.handle}` });
    }
    expect(await feedCount(owner.id, id)).toBe(0);
  });

  it('S-FD-03: post → GET /me/unread shows 1 for B; posting again → 2', async () => {
    const owner = await u('f03a');
    const b = await u('f03b');
    const id = await seedChannel(owner, 'f03', true);
    await seedMember(owner, id, b);
    const c = await client(owner.id);
    (await c.post(`/channels/${id}/messages`, { text: 'one' })).expect(201);
    await awaitUntil(() => unreadFor(b.id, id), (n) => n === 1, { label: 'S-FD-03 first' });
    (await c.post(`/channels/${id}/messages`, { text: 'two' })).expect(201);
    await awaitUntil(() => unreadFor(b.id, id), (n) => n === 2, { label: 'S-FD-03 second' });
  });

  it('S-FD-04: B POST /channels/{id}/read → 204; that channel unread → 0; others untouched', async () => {
    const owner = await u('f04a');
    const b = await u('f04b');
    const id1 = await seedChannel(owner, 'f04-1', true);
    const id2 = await seedChannel(owner, 'f04-2', true);
    await seedMember(owner, id1, b);
    await seedMember(owner, id2, b);
    const c = await client(owner.id);
    (await c.post(`/channels/${id1}/messages`, { text: 'm1' })).expect(201);
    (await c.post(`/channels/${id2}/messages`, { text: 'm2' })).expect(201);
    await awaitUntil(() => unreadFor(b.id, id1), (n) => n === 1, { label: 'S-FD-04 warm1' });
    await awaitUntil(() => unreadFor(b.id, id2), (n) => n === 1, { label: 'S-FD-04 warm2' });

    const cb = await client(b.id);
    (await cb.post(`/channels/${id1}/read`)).expect(204);
    expect(await unreadFor(b.id, id1)).toBe(0);
    expect(await unreadFor(b.id, id2)).toBe(1);
  });

  it('S-FD-05 [G-KAFKA]: re-publish same message.posted → still one feed entry for B AND unread still 1', async () => {
    const fx = await getFixture();
    const owner = await u('f05a');
    const b = await u('f05b');
    const id = await seedChannel(owner, 'f05', true);
    await seedMember(owner, id, b);
    const c = await client(owner.id);
    (await c.post(`/channels/${id}/messages`, { text: 'once' })).expect(201);
    await awaitUntil(() => feedCount(b.id, id), (n) => n === 1, { label: 'S-FD-05 initial' });

    const cb = await client(b.id);
    const entry = (await cb.get('/feed')).json<Page<FeedEntryDto>>().items.find((f) => f.channelId === id);
    expect(entry).toBeDefined();
    await fx.kafka.publish(
      { messageId: entry!.messageId, channelId: id, senderId: owner.id, preview: 'once', postedAt: new Date().toISOString() },
      KAFKA_TOPIC,
    );
    await fx.kafka.awaitConsumed(KAFKA_TOPIC, KAFKA_GROUP);

    expect(await feedCount(b.id, id)).toBe(1);
    expect(await unreadFor(b.id, id)).toBe(1);
  });

  it('S-FD-06 [G-CACHE]: owner kicks B, then A posts → no new feed entry for B; unread not incremented', async () => {
    const owner = await u('f06a');
    const b = await u('f06b');
    const id = await seedChannel(owner, 'f06', true);
    await seedMember(owner, id, b);
    const c = await client(owner.id);
    (await c.post(`/channels/${id}/messages`, { text: 'before kick' })).expect(201);
    await awaitUntil(() => feedCount(b.id, id), (n) => n === 1, { label: 'S-FD-06 warm' });

    (await c.del(`/channels/${id}/members/${b.id}`)).expect(204);
    (await c.post(`/channels/${id}/messages`, { text: 'after kick' })).expect(201);
    // Settle the second event, then assert B saw no growth.
    await getFixture().then((fx) => fx.kafka.awaitConsumed(KAFKA_TOPIC, KAFKA_GROUP));
    expect(await feedCount(b.id, id)).toBe(1);
    expect(await unreadFor(b.id, id)).toBe(1);
  });
});
