// S-AT attachments. The download-authorization scenarios (S-AT-06,07) are the
// G-S3 gallery catching tests (real MinIO + real DB); their naive demo lives in
// tests/gallery/. Uploads/downloads cross the real multipart + object-store path.

import { describe, expect, it } from 'vitest';

import { getFixture } from './fixture-holder.js';
import {
  client,
  dbCount,
  downloadAttachment,
  seedChannel,
  seedMember,
  seedUser,
  uploadAttachment,
  type ApiUser,
} from './helpers.js';

interface AttachmentDto {
  id: string;
  channelId: string;
  filename: string;
  sizeBytes: number;
}

let seq = 0;
async function u(tag: string): Promise<ApiUser> {
  return seedUser(`at_${tag}_${(seq++).toString(36)}_${Date.now().toString(36)}`);
}

describe('S-AT attachments', () => {
  it('S-AT-01: member uploads 10 KiB → 201; bytes in MinIO; metadata correct', async () => {
    const fx = await getFixture();
    const owner = await u('a01');
    const id = await seedChannel(owner, 'a01', true);
    const data = Buffer.alloc(10 * 1024, 7);
    const resp = (await uploadAttachment(fx.baseUrl(), owner.id, id, 'doc.bin', data)).expect(201);
    const dto = resp.json<AttachmentDto>();
    expect(dto.sizeBytes).toBe(data.length);
    expect(dto.filename).toBe('doc.bin');
    const stored = await fx.s3.objectBytes(`${id}/${dto.id}`);
    expect(stored.equals(data)).toBe(true);
    expect(await dbCount('attachments', 'id = $1 AND channel_id = $2', dto.id, id)).toBe(1);
  });

  it('S-AT-02: non-member upload: public → 403, private → 404', async () => {
    const fx = await getFixture();
    const owner = await u('a02o');
    const nonmember = await u('a02n');
    const pub = await seedChannel(owner, 'a02pub', false);
    const priv = await seedChannel(owner, 'a02priv', true);
    const data = Buffer.alloc(16, 1);
    (await uploadAttachment(fx.baseUrl(), nonmember.id, pub, 'x', data)).expect(403).expectCode('channel:membership_required');
    (await uploadAttachment(fx.baseUrl(), nonmember.id, priv, 'x', data)).expect(404).expectCode('channel:not_found');
  });

  it('S-AT-03: file > 1 MiB → 413 attachment:too_large; empty → 422 attachment:empty', async () => {
    const fx = await getFixture();
    const owner = await u('a03');
    const id = await seedChannel(owner, 'a03', true);
    (await uploadAttachment(fx.baseUrl(), owner.id, id, 'big', Buffer.alloc((1 << 20) + 1, 9)))
      .expect(413)
      .expectCode('attachment:too_large');
    (await uploadAttachment(fx.baseUrl(), owner.id, id, 'empty', Buffer.alloc(0)))
      .expect(422)
      .expectCode('attachment:empty');
  });

  it('S-AT-04: reference own attachment → 201 message_id set; another user’s / another channel → 422', async () => {
    const fx = await getFixture();
    const owner = await u('a04o');
    const other = await u('a04x');
    const chA = await seedChannel(owner, 'a04a', true);
    const chB = await seedChannel(owner, 'a04b', true);
    await seedMember(owner, chA, other);
    const mine = (await uploadAttachment(fx.baseUrl(), owner.id, chA, 'mine', Buffer.alloc(8, 1))).expect(201).json<AttachmentDto>();
    const theirs = (await uploadAttachment(fx.baseUrl(), other.id, chA, 'theirs', Buffer.alloc(8, 2))).expect(201).json<AttachmentDto>();
    const otherChannel = (await uploadAttachment(fx.baseUrl(), owner.id, chB, 'elsewhere', Buffer.alloc(8, 3))).expect(201).json<AttachmentDto>();

    const co = await client(owner.id);
    (await co.post(`/channels/${chA}/messages`, { text: 'with mine', attachmentIds: [mine.id] })).expect(201);
    expect(await dbCount('attachments', 'id = $1 AND message_id IS NOT NULL', mine.id)).toBe(1);
    (await co.post(`/channels/${chA}/messages`, { text: 'theirs', attachmentIds: [theirs.id] }))
      .expect(422)
      .expectCode('message:attachment:invalid');
    (await co.post(`/channels/${chA}/messages`, { text: 'wrong channel', attachmentIds: [otherChannel.id] }))
      .expect(422)
      .expectCode('message:attachment:invalid');
  });

  it('S-AT-05: member downloads → 200, byte-identical, filename in Content-Disposition', async () => {
    const fx = await getFixture();
    const owner = await u('a05');
    const id = await seedChannel(owner, 'a05', true);
    const data = Buffer.from('hello attachment bytes');
    const dto = (await uploadAttachment(fx.baseUrl(), owner.id, id, 'note.txt', data)).expect(201).json<AttachmentDto>();
    const dl = (await downloadAttachment(fx.baseUrl(), owner.id, dto.id)).expect(200);
    expect(dl.bytes.equals(data)).toBe(true);
    expect(dl.headers.get('content-disposition')).toContain('note.txt');
  });

  it('S-AT-06 [G-S3]: non-member downloads private-channel attachment → 404, zero bytes, body identical to unknown-id 404', async () => {
    const fx = await getFixture();
    const owner = await u('a06o');
    const nonmember = await u('a06n');
    const id = await seedChannel(owner, 'a06', true);
    const dto = (await uploadAttachment(fx.baseUrl(), owner.id, id, 'secret.bin', Buffer.alloc(32, 5))).expect(201).json<AttachmentDto>();
    const denied = (await downloadAttachment(fx.baseUrl(), nonmember.id, dto.id)).expect(404);
    expect(denied.code()).toBe('attachment:not_found');
    expect(denied.bytes.length).toBeGreaterThan(0); // JSON error body, not file bytes
    const unknown = (await downloadAttachment(fx.baseUrl(), nonmember.id, 'no-such-attachment')).expect(404);
    expect(denied.bytes.toString()).toBe(unknown.bytes.toString());
  });

  it('S-AT-07 [G-S3]: non-member downloads public-channel attachment → 403, zero bytes', async () => {
    const fx = await getFixture();
    const owner = await u('a07o');
    const nonmember = await u('a07n');
    const id = await seedChannel(owner, 'a07', false);
    const dto = (await uploadAttachment(fx.baseUrl(), owner.id, id, 'pubfile.bin', Buffer.alloc(32, 6))).expect(201).json<AttachmentDto>();
    const denied = (await downloadAttachment(fx.baseUrl(), nonmember.id, dto.id)).expect(403);
    expect(denied.code()).toBe('channel:membership_required');
  });
});
