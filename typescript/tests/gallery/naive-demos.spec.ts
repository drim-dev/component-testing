// The naive red→green demonstrations (05-gallery §0.1/§0.4). For each injectable
// gallery case, the catching test's OWN assertion block is run against a naive-
// wired host through expectCatchToFail — which passes BECAUSE the assertions go
// red against the bug. Each is paired with its catching test (the green scenario
// of the same id in the scenario specs, and the lying test in *.lying.spec.ts);
// the README case index records the pairing.
//
// These prove the seam catches what it claims: swap one provider to its default-
// shaped bug and the same component assertions fail.

import { describe, expect, it } from 'vitest';

import { expectCatchToFail } from '../expect-catch-to-fail.js';
import { getFixture } from '../fixture-holder.js';
import {
  ATTACHMENT_ACCESS,
  CHANNEL_READ_GATE,
  CHANNEL_ROLE_GATE,
  CONVERSATION_WRITER,
  DM_ACCESS,
  LINK_PREVIEWER,
  MEMBERSHIP_WRITER,
  MESSAGE_POSTED_PUBLISHER,
  PRESENCE_CLIENT,
  SUMMARIZER,
} from '../../src/seams/seams.js';
import type { LinkPreviewer, MessagePostedPublisher, PresenceClient } from '../../src/seams/seams.js';
import { GrpcPresenceClient } from '../../src/presence/client.js';
import { IdFactory } from '../../src/idgen/idgen.js';
import {
  NaiveAttachmentAccess,
  NaiveChannelReadGate,
  NaiveChannelRoleGate,
  NaiveDmAccess,
  NaiveRaceConversationWriter,
  NaivePresenceClient,
  NaiveSummarizer,
  NaiveTxConversationWriter,
} from '../naive/naive-seams.js';
import {
  awaitUntil,
  client,
  dbCount,
  downloadAttachment,
  seedChannel,
  seedConversation,
  seedDmMessage,
  seedMember,
  seedUser,
  uploadAttachment,
  type ApiUser,
  type Page,
} from '../helpers.js';
import { withNaiveApp } from './gallery-helpers.js';

let seq = 0;
async function u(tag: string): Promise<ApiUser> {
  return seedUser(`gd_${tag}_${(seq++).toString(36)}_${Date.now().toString(36)}`);
}

describe('Gallery naive demos (red→green)', () => {
  it('G-IDOR demo: NaiveDmAccess (load-by-id) → S-DM-08 assertions go red', async () => {
    const a = await u('idor_a');
    const b = await u('idor_b');
    const cUser = await u('idor_c');
    const convId = await seedConversation(a, b);
    const fx = await getFixture();
    await withNaiveApp([{ token: DM_ACCESS, value: new NaiveDmAccess(fx.store) }], async (clientFor) => {
      await expectCatchToFail('G-IDOR', async () => {
        const resp = await clientFor(cUser.id).get(`/dm/conversations/${convId}`);
        resp.expect(404).expectCode('dm:conversation:not_found');
      });
    });
  });

  it('G-RACE demo: NaiveRaceConversationWriter (check-then-insert) → S-DM-05 assertions go red', async () => {
    const a = await u('race_a');
    const b = await u('race_b');
    const fx = await getFixture();
    const [lo, hi] = [a.id, b.id].sort();
    await withNaiveApp(
      [{ token: CONVERSATION_WRITER, value: new NaiveRaceConversationWriter(fx.store, new IdFactory(1)) }],
      async (clientFor) => {
        await expectCatchToFail('G-RACE', async () => {
          const ca = clientFor(a.id);
          const results = await Promise.all(
            Array.from({ length: 10 }, () => ca.post('/dm/conversations', { recipientId: b.id })),
          );
          const ids = new Set(results.map((r) => r.json<{ id: string }>().id));
          expect(ids.size).toBe(1);
          expect(await dbCount('dm_conversations', 'user_lo = $1 AND user_hi = $2', lo, hi)).toBe(1);
        });
      },
    );
  });

  it('G-TX demo: NaiveTxConversationWriter (no transaction) → S-DM-06 assertions go red', async () => {
    const a = await u('tx_a');
    const b = await u('tx_b');
    const fx = await getFixture();
    const [lo, hi] = [a.id, b.id].sort();
    await fx.database.armParticipantInsertFault();
    await withNaiveApp(
      [{ token: CONVERSATION_WRITER, value: new NaiveTxConversationWriter(fx.store, new IdFactory(1)) }],
      async (clientFor) => {
        await expectCatchToFail('G-TX', async () => {
          const resp = await clientFor(a.id).post('/dm/conversations', { recipientId: b.id });
          expect(resp.status).toBe(500);
          expect(await dbCount('dm_conversations', 'user_lo = $1 AND user_hi = $2', lo, hi)).toBe(0);
          expect(await dbCount('dm_participants', 'user_id IN ($1, $2)', a.id, b.id)).toBe(0);
        });
      },
    );
  });

  it('G-BOLA-READ demo: NaiveChannelReadGate (ignore private) → S-CH-05 assertions go red', async () => {
    const owner = await u('bread_o');
    const nonmember = await u('bread_n');
    const id = await seedChannel(owner, 'bread', true);
    const fx = await getFixture();
    await withNaiveApp([{ token: CHANNEL_READ_GATE, value: new NaiveChannelReadGate(fx.store) }], async (clientFor) => {
      await expectCatchToFail('G-BOLA-READ', async () => {
        const resp = await clientFor(nonmember.id).get(`/channels/${id}`);
        resp.expect(404).expectCode('channel:not_found');
      });
    });
  });

  it('G-BOLA-ROLE demo: NaiveChannelRoleGate (skip role compare) → S-CH-15 assertions go red', async () => {
    const owner = await u('brole_o');
    const m1 = await u('brole_m1');
    const m2 = await u('brole_m2');
    const id = await seedChannel(owner, 'brole', true);
    await seedMember(owner, id, m1);
    await seedMember(owner, id, m2);
    const fx = await getFixture();
    await withNaiveApp([{ token: CHANNEL_ROLE_GATE, value: new NaiveChannelRoleGate(fx.store) }], async (clientFor) => {
      await expectCatchToFail('G-BOLA-ROLE', async () => {
        const resp = await clientFor(m1.id).del(`/channels/${id}/members/${m2.id}`);
        resp.expect(403).expectCode('channel:role:forbidden');
        expect(await dbCount('channel_members', 'channel_id = $1 AND user_id = $2', id, m2.id)).toBe(1);
      });
    });
  });

  it('G-S3 demo: NaiveAttachmentAccess (possession is access) → S-AT-06 assertions go red', async () => {
    const fx = await getFixture();
    const owner = await u('s3_o');
    const nonmember = await u('s3_n');
    const id = await seedChannel(owner, 's3', true);
    const dto = (await uploadAttachment(fx.baseUrl(), owner.id, id, 'secret.bin', Buffer.alloc(32, 5)))
      .expect(201)
      .json<{ id: string }>();
    await withNaiveApp([{ token: ATTACHMENT_ACCESS, value: new NaiveAttachmentAccess(fx.store) }], async (_clientFor, handle) => {
      await expectCatchToFail('G-S3', async () => {
        const denied = (await downloadAttachment(handle.baseUrl(), nonmember.id, dto.id)).expect(404);
        expect(denied.code()).toBe('attachment:not_found');
      });
    });
  });

  it('G-HTTP demo: NaiveLinkPreviewer (no timeout) → S-LP-02 assertions go red', async () => {
    const fx = await getFixture();
    fx.unfurl.programDelay(2000);
    const owner = await u('http_o');
    const id = await seedChannel(owner, 'http', true);
    const naive: LinkPreviewer = {
      // No timeout, no guard: awaits the slow upstream directly.
      preview: async (url: string): Promise<string | null> => {
        const resp = await fetch(`${fx.unfurl.getBaseUrl()}/unfurl?url=${encodeURIComponent(url)}`);
        return ((await resp.json()) as { title?: string }).title ?? null;
      },
    };
    await withNaiveApp([{ token: LINK_PREVIEWER, value: naive }], async (clientFor) => {
      await expectCatchToFail('G-HTTP', async () => {
        const start = Date.now();
        const resp = await clientFor(owner.id).post(`/channels/${id}/messages`, { text: 'slow https://slow.example.com' });
        resp.expect(201);
        expect(Date.now() - start).toBeLessThan(1500);
      });
    });
  });

  it('G-GRPC demo: NaivePresenceClient (swallow stream error) → S-PR-04 assertions go red', async () => {
    const fx = await getFixture();
    const owner = await u('grpc_o');
    const members = await Promise.all([u('grpc_b'), u('grpc_c'), u('grpc_d'), u('grpc_e')]);
    const id = await seedChannel(owner, 'grpc', true);
    for (const m of members) {
      await seedMember(owner, id, m);
    }
    fx.presence.failStreamAfter(2);
    const naive: PresenceClient = new NaivePresenceClient(new GrpcPresenceClient(fx.presence.address));
    await withNaiveApp([{ token: PRESENCE_CLIENT, value: naive }], async (clientFor) => {
      await expectCatchToFail('G-GRPC', async () => {
        const resp = await clientFor(owner.id).get(`/channels/${id}/presence`);
        resp.expect(502).expectCode('presence:incomplete');
      });
    });
  });

  it('G-LLM demo: NaiveSummarizer (raw text in instruction, no output check) → S-SM-04 assertions go red', async () => {
    const fx = await getFixture();
    fx.llm.programResponse('x'.repeat(5000));
    const owner = await u('llm_o');
    const id = await seedChannel(owner, 'llm', true);
    (await (await client(owner.id)).post(`/channels/${id}/messages`, { text: 'hello there' })).expect(201);
    await withNaiveApp([{ token: SUMMARIZER, value: new NaiveSummarizer(fx.llm.model()) }], async (clientFor) => {
      await expectCatchToFail('G-LLM', async () => {
        const resp = await clientFor(owner.id).post(`/channels/${id}/summary`, {});
        resp.expect(502).expectCode('summary:invalid_output');
      });
    });
  });

  it('G-CACHE demo: NaiveMembershipWriter (forget invalidation) → cache-divergence assertion goes red', async () => {
    const fx = await getFixture();
    const owner = await u('cache_o');
    const member = await u('cache_m');
    const id = await seedChannel(owner, 'cache', true);
    await seedMember(owner, id, member);
    // Warm the membership cache so a forgotten invalidation is observable as a stale set.
    await fx.redis.seedMembershipCache(id, owner.id, member.id);
    const { NaiveMembershipWriter } = await import('../naive/naive-seams.js');
    await withNaiveApp([{ token: MEMBERSHIP_WRITER, value: new NaiveMembershipWriter(fx.store) }], async (clientFor) => {
      await expectCatchToFail('G-CACHE', async () => {
        (await clientFor(owner.id).del(`/channels/${id}/members/${member.id}`)).expect(204);
        const cache = await fx.redis.cacheHasMember(id, member.id);
        // Correct writer invalidates → key gone or member absent. Naive leaves it stale.
        expect(cache.exists === false || cache.member === false).toBe(true);
      });
    });
  });

  it('G-KAFKA producer demo: fire-and-forget publish → S-FD-01 assertions go red', async () => {
    const fx = await getFixture();
    const owner = await u('kprod_o');
    const id = await seedChannel(owner, 'kprod', true);
    // Naive producer: send without awaiting confirmation, swallow the result.
    const naive: MessagePostedPublisher = {
      publish: (): Promise<void> => {
        // Fire-and-forget: nothing awaited, no 503 when the broker is down.
        return Promise.resolve();
      },
    };
    await fx.kafka.stopBroker();
    try {
      await withNaiveApp([{ token: MESSAGE_POSTED_PUBLISHER, value: naive }], async (clientFor) => {
        await expectCatchToFail('G-KAFKA', async () => {
          const resp = await clientFor(owner.id).post(`/channels/${id}/messages`, { text: 'lost?' });
          resp.expect(503).expectCode('events:unavailable');
          expect(await dbCount('channel_messages', 'channel_id = $1', id)).toBe(0);
        });
      });
    } finally {
      await fx.kafka.startBroker();
    }
  });

  it('G-RABBIT demo: NaiveNotificationRecorder (insert-or-crash) → redelivered duplicate dead-letters (S-NT-02 DLQ-empty goes red)', async () => {
    const fx = await getFixture();
    const { naiveWorkers } = await import('../../harness/naive-app.js');
    const { NaiveNotificationRecorder } = await import('../naive/naive-seams.js');
    const { RABBIT_NAIVE_QUEUE } = await import('../../harness/rabbitmq.harness.js');
    const { deadLetterQueue } = await import('../../src/infra/rabbit-infra.js');

    const a = await u('rabbit_a');
    const b = await u('rabbit_b');
    const convId = await seedConversation(a, b);
    await seedDmMessage(a, convId, 'rabbit dup');
    // Capture the real dm message id the correct worker recorded.
    const cb = await client(b.id);
    const dmMessageId = await awaitUntil(
      async () => (await cb.get('/notifications')).json<Page<{ dmMessageId: string }>>().items,
      (xs) => xs.length === 1,
      { label: 'G-RABBIT seed' },
    ).then((xs) => xs[0].dmMessageId);

    const naiveWorker = await naiveWorkers(fx, ({ projector }) => ({
      projector,
      recorder: new NaiveNotificationRecorder(fx.store, new IdFactory(1)),
    }));
    try {
      await expectCatchToFail('G-RABBIT', async () => {
        // Publish a DUPLICATE of an already-recorded job onto the NAIVE queue. The
        // naive recorder hits UNIQUE(dm_message_id), crash-loops, and dead-letters.
        await fx.rabbit.publish(
          { dmMessageId, conversationId: convId, senderId: a.id, recipientId: b.id, preview: 'rabbit dup' },
          RABBIT_NAIVE_QUEUE,
        );
        await fx.rabbit.awaitSettled(RABBIT_NAIVE_QUEUE);
        // The catching assertion: the DLQ stays empty. Against the naive recorder
        // it does NOT (the duplicate dead-letters), so this block throws → red.
        expect(await fx.rabbit.readyCount(deadLetterQueue(RABBIT_NAIVE_QUEUE))).toBe(0);
      });
    } finally {
      await naiveWorker.close();
    }
  });

  it('G-KAFKA consumer demo: NaiveFeedProjector (non-idempotent) → redelivery diverges counter (S-FD-05 goes red)', async () => {
    const fx = await getFixture();
    const { naiveWorkers } = await import('../../harness/naive-app.js');
    const { NaiveFeedProjector } = await import('../naive/naive-seams.js');
    const { KAFKA_NAIVE_TOPIC, KAFKA_NAIVE_GROUP } = await import('../../harness/kafka.harness.js');
    const { RedisUnreadCounters } = await import('../../src/infra/redis-infra.js');

    const owner = await u('kcons_o');
    const b = await u('kcons_b');
    const id = await seedChannel(owner, 'kcons', true);
    await seedMember(owner, id, b);

    const ids = new IdFactory(1);
    const unread = new RedisUnreadCounters(fx.redis.client);
    const naiveWorker = await naiveWorkers(fx, () => ({
      projector: new NaiveFeedProjector(fx.store, unread, ids),
      recorder: { record: () => Promise.resolve() },
    }));
    try {
      const messageId = ids.create();
      const ev = { messageId, channelId: id, senderId: owner.id, preview: 'k', postedAt: new Date().toISOString() };
      await expectCatchToFail('G-KAFKA', async () => {
        // Publish the SAME event twice on the naive topic. The idempotent projector
        // would keep the counter at 1; the naive one increments twice.
        await fx.kafka.publish(ev, KAFKA_NAIVE_TOPIC);
        await fx.kafka.awaitConsumed(KAFKA_NAIVE_TOPIC, KAFKA_NAIVE_GROUP);
        await fx.kafka.publish(ev, KAFKA_NAIVE_TOPIC);
        await fx.kafka.awaitConsumed(KAFKA_NAIVE_TOPIC, KAFKA_NAIVE_GROUP);
        const cb = await client(b.id);
        const unreadDto = (await cb.get('/me/unread')).json<{ channels: Record<string, number> }>();
        // Correct: exactly 1 (idempotent). Naive: 2 → this assertion fails → red.
        expect(unreadDto.channels[id] ?? 0).toBe(1);
      });
    } finally {
      await naiveWorker.close();
    }
  });
});
