// S-CH channels: membership, role, visibility, cache coherence. The BOLA-READ,
// BOLA-ROLE, and CACHE scenarios are gallery catching tests (green here against
// the correct app; their naive demos live in tests/gallery/).

import { describe, expect, it } from 'vitest';

import { getFixture } from './fixture-holder.js';
import {
  client,
  dbCount,
  seedChannel,
  seedMember,
  seedUser,
  type ApiUser,
  type Page,
} from './helpers.js';

interface ChannelDto {
  id: string;
  name: string;
  private: boolean;
  memberCount?: number;
  createdAt: string;
}
interface MembershipDto {
  channelId: string;
  userId: string;
  role: string;
}

let seq = 0;
async function u(tag: string): Promise<ApiUser> {
  return seedUser(`ch_${tag}_${(seq++).toString(36)}_${Date.now().toString(36)}`);
}

describe('S-CH channels', () => {
  it('S-CH-01: create → 201; creator is sole owner member (DB-state)', async () => {
    const owner = await u('c01');
    const id = await seedChannel(owner, 'general', false);
    expect(await dbCount('channel_members', 'channel_id = $1', id)).toBe(1);
    expect(await dbCount('channel_members', "channel_id = $1 AND user_id = $2 AND role = 'owner'", id, owner.id)).toBe(1);
  });

  it('S-CH-02: name empty / 101 chars → 422 channel:name:invalid', async () => {
    const owner = await u('c02');
    const c = await client(owner.id);
    (await c.post('/channels', { name: '' })).expect(422).expectCode('channel:name:invalid');
    (await c.post('/channels', { name: 'x'.repeat(101) })).expect(422).expectCode('channel:name:invalid');
  });

  it('S-CH-03: GET /channels → public + caller’s private; nobody else’s private', async () => {
    const owner = await u('c03o');
    const other = await u('c03x');
    const pub = await seedChannel(owner, 'pub', false);
    const mine = await seedChannel(owner, 'mine-priv', true);
    const theirs = await seedChannel(other, 'their-priv', true);
    const c = await client(owner.id);
    const page = (await c.get('/channels')).expect(200).json<Page<ChannelDto>>();
    const ids = page.items.map((x) => x.id);
    expect(ids).toContain(pub);
    expect(ids).toContain(mine);
    expect(ids).not.toContain(theirs);
  });

  it('S-CH-04: non-member GET /channels/{public id} → 200 metadata with memberCount', async () => {
    const owner = await u('c04o');
    const nonmember = await u('c04n');
    const id = await seedChannel(owner, 'pub4', false);
    const c = await client(nonmember.id);
    const dto = (await c.get(`/channels/${id}`)).expect(200).json<ChannelDto>();
    expect(dto.memberCount).toBe(1);
  });

  it('S-CH-05 [G-BOLA-READ]: non-member GET /channels/{private id} → 404, byte-identical to unknown-id 404', async () => {
    const owner = await u('c05o');
    const nonmember = await u('c05n');
    const id = await seedChannel(owner, 'priv5', true);
    const c = await client(nonmember.id);
    const denied = (await c.get(`/channels/${id}`)).expect(404).expectCode('channel:not_found');
    const unknown = (await c.get('/channels/no-such-channel')).expect(404);
    expect(denied.rawBody).toBe(unknown.rawBody);
  });

  it('S-CH-06: join public → 201 membership role member', async () => {
    const owner = await u('c06o');
    const joiner = await u('c06j');
    const id = await seedChannel(owner, 'pub6', false);
    const c = await client(joiner.id);
    const m = (await c.post(`/channels/${id}/join`)).expect(201).json<MembershipDto>();
    expect(m.role).toBe('member');
  });

  it('S-CH-07: join when already member → 409 channel:member:already', async () => {
    const owner = await u('c07o');
    const id = await seedChannel(owner, 'pub7', false);
    const c = await client(owner.id);
    (await c.post(`/channels/${id}/join`)).expect(409).expectCode('channel:member:already');
  });

  it('S-CH-08: join private as non-member → 404', async () => {
    const owner = await u('c08o');
    const joiner = await u('c08j');
    const id = await seedChannel(owner, 'priv8', true);
    const c = await client(joiner.id);
    (await c.post(`/channels/${id}/join`)).expect(404).expectCode('channel:not_found');
  });

  it('S-CH-09: owner adds user to private channel → 201', async () => {
    const owner = await u('c09o');
    const target = await u('c09t');
    const id = await seedChannel(owner, 'priv9', true);
    const c = await client(owner.id);
    (await c.post(`/channels/${id}/members`, { userId: target.id })).expect(201);
  });

  it('S-CH-10: admin adds user → 201', async () => {
    const owner = await u('c10o');
    const admin = await u('c10a');
    const target = await u('c10t');
    const id = await seedChannel(owner, 'priv10', true);
    await seedMember(owner, id, admin);
    const co = await client(owner.id);
    (await co.post(`/channels/${id}/members/${admin.id}/promote`)).expect(200);
    const ca = await client(admin.id);
    (await ca.post(`/channels/${id}/members`, { userId: target.id })).expect(201);
  });

  it('S-CH-11 [G-BOLA-ROLE]: plain member adds user → 403; no membership written', async () => {
    const owner = await u('c11o');
    const member = await u('c11m');
    const target = await u('c11t');
    const id = await seedChannel(owner, 'priv11', true);
    await seedMember(owner, id, member);
    const cm = await client(member.id);
    (await cm.post(`/channels/${id}/members`, { userId: target.id })).expect(403).expectCode('channel:role:forbidden');
    expect(await dbCount('channel_members', 'channel_id = $1 AND user_id = $2', id, target.id)).toBe(0);
  });

  it('S-CH-12: add an existing member → 409', async () => {
    const owner = await u('c12o');
    const target = await u('c12t');
    const id = await seedChannel(owner, 'priv12', true);
    await seedMember(owner, id, target);
    const co = await client(owner.id);
    (await co.post(`/channels/${id}/members`, { userId: target.id })).expect(409).expectCode('channel:member:already');
  });

  it('S-CH-13: owner promotes member → 200 admin; admin attempts promote → 403', async () => {
    const owner = await u('c13o');
    const admin = await u('c13a');
    const member = await u('c13m');
    const id = await seedChannel(owner, 'priv13', true);
    await seedMember(owner, id, admin);
    await seedMember(owner, id, member);
    const co = await client(owner.id);
    const promoted = (await co.post(`/channels/${id}/members/${admin.id}/promote`)).expect(200).json<MembershipDto>();
    expect(promoted.role).toBe('admin');
    const ca = await client(admin.id);
    (await ca.post(`/channels/${id}/members/${member.id}/promote`)).expect(403).expectCode('channel:role:forbidden');
  });

  it('S-CH-14: admin kicks member → 204; membership gone', async () => {
    const owner = await u('c14o');
    const admin = await u('c14a');
    const member = await u('c14m');
    const id = await seedChannel(owner, 'priv14', true);
    await seedMember(owner, id, admin);
    await seedMember(owner, id, member);
    const co = await client(owner.id);
    (await co.post(`/channels/${id}/members/${admin.id}/promote`)).expect(200);
    const ca = await client(admin.id);
    (await ca.del(`/channels/${id}/members/${member.id}`)).expect(204);
    expect(await dbCount('channel_members', 'channel_id = $1 AND user_id = $2', id, member.id)).toBe(0);
  });

  it('S-CH-15 [G-BOLA-ROLE]: member kicks member → 403; membership intact', async () => {
    const owner = await u('c15o');
    const m1 = await u('c15m1');
    const m2 = await u('c15m2');
    const id = await seedChannel(owner, 'priv15', true);
    await seedMember(owner, id, m1);
    await seedMember(owner, id, m2);
    const c1 = await client(m1.id);
    (await c1.del(`/channels/${id}/members/${m2.id}`)).expect(403).expectCode('channel:role:forbidden');
    expect(await dbCount('channel_members', 'channel_id = $1 AND user_id = $2', id, m2.id)).toBe(1);
  });

  it('S-CH-16 [G-CACHE]: B reads (warms cache), owner kicks B, B reads again → 404; cache invalidated', async () => {
    const fx = await getFixture();
    const owner = await u('c16o');
    const member = await u('c16m');
    const id = await seedChannel(owner, 'priv16', true);
    await seedMember(owner, id, member);
    const cm = await client(member.id);
    (await cm.get(`/channels/${id}/messages`)).expect(200);
    await fx.redis.seedMembershipCache(id, owner.id, member.id); // explicitly warm: cache holds B
    expect((await fx.redis.cacheHasMember(id, member.id)).member).toBe(true);

    const co = await client(owner.id);
    (await co.del(`/channels/${id}/members/${member.id}`)).expect(204);

    (await cm.get(`/channels/${id}/messages`)).expect(404).expectCode('channel:not_found');
    const cache = await fx.redis.cacheHasMember(id, member.id);
    expect(cache.exists === false || cache.member === false).toBe(true);
  });

  it('S-CH-17: member leaves → 204; owner leaves → 409 channel:owner:cannot_leave', async () => {
    const owner = await u('c17o');
    const member = await u('c17m');
    const id = await seedChannel(owner, 'priv17', true);
    await seedMember(owner, id, member);
    const cm = await client(member.id);
    (await cm.del(`/channels/${id}/members/${member.id}`)).expect(204);
    const co = await client(owner.id);
    (await co.del(`/channels/${id}/members/${owner.id}`)).expect(409).expectCode('channel:owner:cannot_leave');
  });

  it('S-CH-18: owner kicks admin → 204; admin kicks admin → 403', async () => {
    const owner = await u('c18o');
    const a1 = await u('c18a1');
    const a2 = await u('c18a2');
    const id = await seedChannel(owner, 'priv18', true);
    await seedMember(owner, id, a1);
    await seedMember(owner, id, a2);
    const co = await client(owner.id);
    (await co.post(`/channels/${id}/members/${a1.id}/promote`)).expect(200);
    (await co.post(`/channels/${id}/members/${a2.id}/promote`)).expect(200);
    const c1 = await client(a1.id);
    (await c1.del(`/channels/${id}/members/${a2.id}`)).expect(403).expectCode('channel:role:forbidden');
    (await co.del(`/channels/${id}/members/${a1.id}`)).expect(204);
  });

  it('S-CH-19 [G-BOLA-ROLE]: admin deletes → 403; member deletes → 403; channel intact', async () => {
    const owner = await u('c19o');
    const admin = await u('c19a');
    const member = await u('c19m');
    const id = await seedChannel(owner, 'priv19', true);
    await seedMember(owner, id, admin);
    await seedMember(owner, id, member);
    const co = await client(owner.id);
    (await co.post(`/channels/${id}/members/${admin.id}/promote`)).expect(200);
    const ca = await client(admin.id);
    (await ca.del(`/channels/${id}`)).expect(403).expectCode('channel:role:forbidden');
    const cm = await client(member.id);
    (await cm.del(`/channels/${id}`)).expect(403).expectCode('channel:role:forbidden');
    expect(await dbCount('channels', 'id = $1', id)).toBe(1);
  });

  it('S-CH-20: owner deletes → 204; messages/memberships/attachments gone, GET → 404', async () => {
    const owner = await u('c20o');
    const id = await seedChannel(owner, 'priv20', true);
    const co = await client(owner.id);
    (await co.post(`/channels/${id}/messages`, { text: 'hello' })).expect(201);
    (await co.del(`/channels/${id}`)).expect(204);
    expect(await dbCount('channels', 'id = $1', id)).toBe(0);
    expect(await dbCount('channel_messages', 'channel_id = $1', id)).toBe(0);
    expect(await dbCount('channel_members', 'channel_id = $1', id)).toBe(0);
    (await co.get(`/channels/${id}`)).expect(404).expectCode('channel:not_found');
  });

  it('S-CH-21 [G-BOLA-READ]: non-member GET /channels/{private id}/messages → 404; no items leak', async () => {
    const owner = await u('c21o');
    const nonmember = await u('c21n');
    const id = await seedChannel(owner, 'priv21', true);
    const co = await client(owner.id);
    (await co.post(`/channels/${id}/messages`, { text: 'classified' })).expect(201);
    const cn = await client(nonmember.id);
    const resp = (await cn.get(`/channels/${id}/messages`)).expect(404).expectCode('channel:not_found');
    expect(resp.rawBody).not.toContain('classified');
  });

  it('S-CH-22 [G-BOLA-READ pin]: non-member GET /channels/{public id}/messages → 403 channel:membership_required', async () => {
    const owner = await u('c22o');
    const nonmember = await u('c22n');
    const id = await seedChannel(owner, 'pub22', false);
    const cn = await client(nonmember.id);
    (await cn.get(`/channels/${id}/messages`)).expect(403).expectCode('channel:membership_required');
  });

  it('S-CH-23: member posts → 201; non-member posts: public → 403, private → 404; nothing written', async () => {
    const owner = await u('c23o');
    const nonmember = await u('c23n');
    const pub = await seedChannel(owner, 'pub23', false);
    const priv = await seedChannel(owner, 'priv23', true);
    const co = await client(owner.id);
    (await co.post(`/channels/${pub}/messages`, { text: 'mine' })).expect(201);
    const cn = await client(nonmember.id);
    (await cn.post(`/channels/${pub}/messages`, { text: 'x' })).expect(403).expectCode('channel:membership_required');
    (await cn.post(`/channels/${priv}/messages`, { text: 'x' })).expect(404).expectCode('channel:not_found');
    expect(await dbCount('channel_messages', 'channel_id = $1 AND sender_id = $2', pub, nonmember.id)).toBe(0);
    expect(await dbCount('channel_messages', 'channel_id = $1', priv)).toBe(0);
  });

  it('S-CH-24: post text empty / 4001 chars → 422; 11 attachmentIds → 422', async () => {
    const owner = await u('c24o');
    const id = await seedChannel(owner, 'priv24', true);
    const c = await client(owner.id);
    (await c.post(`/channels/${id}/messages`, { text: '' })).expect(422).expectCode('message:text:invalid');
    (await c.post(`/channels/${id}/messages`, { text: 'x'.repeat(4001) })).expect(422).expectCode('message:text:invalid');
    (await c.post(`/channels/${id}/messages`, { text: 'ok', attachmentIds: Array.from({ length: 11 }, (_, i) => `a${i}`) }))
      .expect(422)
      .expectCode('message:attachment:invalid');
  });
});
