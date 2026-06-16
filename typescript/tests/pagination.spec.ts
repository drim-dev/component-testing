// S-PG pagination pins [G-WEAKVAL]. Canonical endpoint: GET
// /channels/{id}/messages as a member. The strict limit bounds (1–100, never
// clamped) are the deterministic pin the §3 weakened-validation gaming story
// rewrites instead of fixing — so they are asserted once here, exactly.

import { describe, expect, it } from 'vitest';

import { client, seedChannel, seedUser } from './helpers.js';
import type { Page } from './helpers.js';

async function memberChannelWithMessages(count: number): Promise<{ ownerId: string; channelId: string }> {
  const owner = await seedUser(`pg_owner_${Date.now().toString(36)}`);
  const channelId = await seedChannel(owner, 'pg-channel', false);
  const c = await client(owner.id);
  for (let i = 0; i < count; i++) {
    (await c.post(`/channels/${channelId}/messages`, { text: `m${i}` })).expect(201);
  }
  return { ownerId: owner.id, channelId };
}

describe('S-PG pagination [G-WEAKVAL]', () => {
  it('S-PG-01: limit=0 → 422 pagination:limit:out_of_range', async () => {
    const { ownerId, channelId } = await memberChannelWithMessages(0);
    const c = await client(ownerId);
    (await c.get(`/channels/${channelId}/messages?limit=0`))
      .expect(422)
      .expectCode('pagination:limit:out_of_range');
  });

  it('S-PG-02: limit=101 → 422 pagination:limit:out_of_range', async () => {
    const { ownerId, channelId } = await memberChannelWithMessages(0);
    const c = await client(ownerId);
    (await c.get(`/channels/${channelId}/messages?limit=101`))
      .expect(422)
      .expectCode('pagination:limit:out_of_range');
  });

  it('S-PG-03: limit=abc → 422 pagination:limit:not_a_number', async () => {
    const { ownerId, channelId } = await memberChannelWithMessages(0);
    const c = await client(ownerId);
    (await c.get(`/channels/${channelId}/messages?limit=abc`))
      .expect(422)
      .expectCode('pagination:limit:not_a_number');
  });

  it('S-PG-04: before=<id never returned> → 422 pagination:before:unknown', async () => {
    const { ownerId, channelId } = await memberChannelWithMessages(1);
    const c = await client(ownerId);
    (await c.get(`/channels/${channelId}/messages?before=never-returned-id`))
      .expect(422)
      .expectCode('pagination:before:unknown');
  });

  it('S-PG-05: 60 messages → default 50 newest-first, before=nextBefore → remaining 10, nextBefore null', async () => {
    const { ownerId, channelId } = await memberChannelWithMessages(60);
    const c = await client(ownerId);

    const first = (await c.get(`/channels/${channelId}/messages`)).expect(200).json<Page<{ id: string; text: string }>>();
    expect(first.items).toHaveLength(50);
    expect(first.items[0].text).toBe('m59'); // newest first
    expect(first.items[49].text).toBe('m10');
    expect(first.nextBefore).not.toBeNull();

    const second = (await c.get(`/channels/${channelId}/messages?before=${first.nextBefore}`))
      .expect(200)
      .json<Page<{ id: string; text: string }>>();
    expect(second.items).toHaveLength(10);
    expect(second.items[0].text).toBe('m9');
    expect(second.items[9].text).toBe('m0');
    expect(second.nextBefore).toBeNull();
  });
});
