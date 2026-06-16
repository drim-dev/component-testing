// S-NT notifications / RabbitMQ. The redelivery/poison/recovery scenarios
// (S-NT-02,03,04) are the G-RABBIT gallery catches: real broker, harness forces
// redelivery / poison, await the queue to settle. Their naive red→green demo
// lives in tests/gallery/.

import { describe, expect, it } from 'vitest';

import { getFixture } from './fixture-holder.js';
import { deadLetterQueue } from '../src/infra/rabbit-infra.js';
import { RABBIT_QUEUE } from '../harness/rabbitmq.harness.js';
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

interface NotificationDto {
  id: string;
  type: string;
  dmMessageId: string;
  senderId: string;
  preview: string;
}

let seq = 0;
async function pair(tag: string): Promise<[ApiUser, ApiUser]> {
  const a = await seedUser(`nt_${tag}_a_${(seq++).toString(36)}`);
  const b = await seedUser(`nt_${tag}_b_${(seq++).toString(36)}`);
  return [a, b];
}

describe('S-NT notifications', () => {
  it('S-NT-01: A DMs B → exactly one notification for B (type, dmMessageId, 100-char preview); none for A', async () => {
    const fx = await getFixture();
    const [a, b] = await pair('01');
    const convId = await seedConversation(a, b);
    const text = 'x'.repeat(150);
    await seedDmMessage(a, convId, text);

    const cb = await client(b.id);
    const items = await awaitUntil(
      async () => (await cb.get('/notifications')).json<Page<NotificationDto>>().items,
      (xs) => xs.length === 1,
      { label: 'S-NT-01' },
    );
    expect(items[0].type).toBe('dm.message');
    expect(items[0].senderId).toBe(a.id);
    expect([...items[0].preview]).toHaveLength(100);

    const ca = await client(a.id);
    expect((await ca.get('/notifications')).json<Page<NotificationDto>>().items).toHaveLength(0);
    void fx;
  });

  it('S-NT-02 [G-RABBIT]: forced redelivery → exactly one notifications row AND DLQ stays empty', async () => {
    const fx = await getFixture();
    const [a, b] = await pair('02');
    const convId = await seedConversation(a, b);
    await seedDmMessage(a, convId, 'redeliver me');
    await fx.rabbit.awaitSettled(RABBIT_QUEUE);

    const dmMessageId = await awaitUntil(
      async () => {
        const cb = await client(b.id);
        return (await cb.get('/notifications')).json<Page<NotificationDto>>().items;
      },
      (xs) => xs.length === 1,
      { label: 'S-NT-02 initial' },
    ).then((xs) => xs[0].dmMessageId);

    // Force redelivery: re-publish the SAME job onto the real queue.
    await fx.rabbit.publish(
      { dmMessageId, conversationId: convId, senderId: a.id, recipientId: b.id, preview: 'redeliver me' },
      RABBIT_QUEUE,
    );
    await fx.rabbit.awaitSettled(RABBIT_QUEUE);

    expect(await dbCount('notifications', 'dm_message_id = $1', dmMessageId)).toBe(1);
    expect(await fx.rabbit.readyCount(deadLetterQueue(RABBIT_QUEUE))).toBe(0);
  });

  it('S-NT-03 [G-RABBIT]: poison job (unresolvable recipient) → DLQ after 3 attempts; zero notification rows', async () => {
    const fx = await getFixture();
    const [a, b] = await pair('03');
    const convId = await seedConversation(a, b);
    await seedDmMessage(a, convId, 'real');
    await fx.rabbit.awaitSettled(RABBIT_QUEUE);
    // Poison: recipient FK does not resolve → insert fails every attempt.
    await fx.rabbit.publish(
      { dmMessageId: 'poison-dm-id', conversationId: convId, senderId: a.id, recipientId: 'ghost-recipient', preview: 'poison' },
      RABBIT_QUEUE,
    );
    await fx.rabbit.awaitDepth(deadLetterQueue(RABBIT_QUEUE), 1);
    expect(await dbCount('notifications', 'dm_message_id = $1', 'poison-dm-id')).toBe(0);
    void b;
  });

  it('S-NT-04 [G-RABBIT]: poison then valid → valid still processed; main queue drains empty', async () => {
    const fx = await getFixture();
    const [a, b] = await pair('04');
    const convId = await seedConversation(a, b);
    await fx.rabbit.publish(
      { dmMessageId: 'poison-04', conversationId: convId, senderId: a.id, recipientId: 'ghost-04', preview: 'poison' },
      RABBIT_QUEUE,
    );
    await seedDmMessage(a, convId, 'valid after poison');

    const items = await awaitUntil(
      async () => {
        const cb = await client(b.id);
        return (await cb.get('/notifications')).json<Page<NotificationDto>>().items;
      },
      (xs) => xs.length === 1,
      { label: 'S-NT-04 valid' },
    );
    expect(items[0].preview).toBe('valid after poison');
    await fx.rabbit.awaitSettled(RABBIT_QUEUE);
    expect(await fx.rabbit.readyCount(RABBIT_QUEUE)).toBe(0);
    await fx.rabbit.awaitDepth(deadLetterQueue(RABBIT_QUEUE), 1);
  });

  it('S-NT-05: GET /notifications returns only the caller’s, newest-first, paginated', async () => {
    const a = await seedUser('nt05_a');
    const b = await seedUser('nt05_b');
    const d = await seedUser('nt05_d');
    const convAB = await seedConversation(a, b);
    await seedDmMessage(a, convAB, 'to-b-one');
    await seedDmMessage(a, convAB, 'to-b-two');
    const convAD = await seedConversation(a, d);
    await seedDmMessage(a, convAD, 'to-d');

    const cb = await client(b.id);
    const items = await awaitUntil(
      async () => (await cb.get('/notifications')).json<Page<NotificationDto>>().items,
      (xs) => xs.length === 2,
      { label: 'S-NT-05' },
    );
    expect(items.map((n) => n.preview)).toEqual(['to-b-two', 'to-b-one']); // newest-first
  });
});
