// S-PR presence / gRPC. The partial-stream scenario (S-PR-04) is the G-GRPC
// gallery catch against the REAL companion-owned gRPC service over a real socket;
// its naive demo lives in tests/gallery/. S-PR-03 is the happy stream counterpart.

import { describe, expect, it } from 'vitest';

import { getFixture } from './fixture-holder.js';
import { client, seedChannel, seedMember, seedUser, type ApiUser } from './helpers.js';

interface PresenceDto {
  userId: string;
  status: string;
}
interface ChannelPresenceDto {
  members: PresenceDto[];
}

let seq = 0;
async function u(tag: string): Promise<ApiUser> {
  return seedUser(`pr_${tag}_${(seq++).toString(36)}_${Date.now().toString(36)}`);
}

describe('S-PR presence', () => {
  it('S-PR-01: presence reports B online → A GET /users/{B}/presence → 200 online (unary)', async () => {
    const fx = await getFixture();
    const a = await u('p01a');
    const b = await u('p01b');
    fx.presence.setOnline(b.id);
    const ca = await client(a.id);
    const dto = (await ca.get(`/users/${b.id}/presence`)).expect(200).json<PresenceDto>();
    expect(dto.status).toBe('online');
  });

  it('S-PR-02: no heartbeat for C → offline', async () => {
    const a = await u('p02a');
    const cUser = await u('p02c');
    const ca = await client(a.id);
    const dto = (await ca.get(`/users/${cUser.id}/presence`)).expect(200).json<PresenceDto>();
    expect(dto.status).toBe('offline');
  });

  it('S-PR-03: channel of 5, 2 online → member GET channel presence → 200, 5 entries, statuses correct', async () => {
    const owner = await u('p03o');
    const members = await Promise.all([u('p03b'), u('p03c'), u('p03d'), u('p03e')]);
    const id = await seedChannel(owner, 'p03', true);
    for (const m of members) {
      await seedMember(owner, id, m);
    }
    // Two online: owner + members[0].
    const fx = await getFixture();
    fx.presence.setOnline(owner.id);
    fx.presence.setOnline(members[0].id);

    const co = await client(owner.id);
    const dto = (await co.get(`/channels/${id}/presence`)).expect(200).json<ChannelPresenceDto>();
    expect(dto.members).toHaveLength(5);
    const online = new Set(dto.members.filter((m) => m.status === 'online').map((m) => m.userId));
    expect(online).toEqual(new Set([owner.id, members[0].id]));
  });

  it('S-PR-04 [G-GRPC]: stream fails after 2 → channel presence → 502 presence:incomplete; no partial list', async () => {
    const fx = await getFixture();
    const owner = await u('p04o');
    const members = await Promise.all([u('p04b'), u('p04c'), u('p04d'), u('p04e')]);
    const id = await seedChannel(owner, 'p04', true);
    for (const m of members) {
      await seedMember(owner, id, m);
    }
    fx.presence.failStreamAfter(2);
    const co = await client(owner.id);
    const resp = (await co.get(`/channels/${id}/presence`)).expect(502).expectCode('presence:incomplete');
    expect(resp.rawBody).not.toContain('"members"');
  });

  it('S-PR-05: non-member channel presence: public → 403, private → 404', async () => {
    const owner = await u('p05o');
    const nonmember = await u('p05n');
    const pub = await seedChannel(owner, 'p05pub', false);
    const priv = await seedChannel(owner, 'p05priv', true);
    const cn = await client(nonmember.id);
    (await cn.get(`/channels/${pub}/presence`)).expect(403).expectCode('channel:membership_required');
    (await cn.get(`/channels/${priv}/presence`)).expect(404).expectCode('channel:not_found');
  });
});
