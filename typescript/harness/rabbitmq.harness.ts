// The RabbitMQ harness (queues / acks / DLQ — different semantics from Kafka's
// log/offsets). Seed = publish a job directly (incl. duplicate and poison);
// Assert = await-until on queue stats (ready via passive declare; unacked via the
// management API, which refreshes on an interval — so "settled" must hold across
// two spaced samples); Reset = purge + drain.

import { connect, type Channel, type ChannelModel } from 'amqplib';
import { RabbitMQContainer, type StartedRabbitMQContainer } from '@testcontainers/rabbitmq';

import type { NotificationJob } from '../src/domain/domain.js';
import { declareNotificationQueues, deadLetterQueue, serializeJob } from '../src/infra/rabbit-infra.js';
import type { DependencyHarness } from './dependency-harness.js';
import { RABBITMQ_IMAGE } from './images.js';

export const RABBIT_QUEUE = 'notify.dm';
export const RABBIT_NAIVE_QUEUE = 'notify.dm.naive';

const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

export class RabbitMqHarness implements DependencyHarness {
  private container?: StartedRabbitMQContainer;
  private conn?: ChannelModel;
  private ch?: Channel;
  private amqpUrl = '';
  private mgmtUrl = '';
  private mgmtUser = 'guest';
  private mgmtPass = 'guest';

  get connection(): ChannelModel {
    if (!this.conn) {
      throw new Error('RabbitMqHarness not started');
    }
    return this.conn;
  }

  async start(): Promise<void> {
    this.container = await new RabbitMQContainer(RABBITMQ_IMAGE)
      .withEnvironment({
        RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS: '-rabbit collect_statistics_interval 500',
      })
      .start();
    this.amqpUrl = this.container.getAmqpUrl();
    this.conn = await connect(this.amqpUrl);
    this.ch = await this.conn.createChannel();
    await declareNotificationQueues(this.ch, RABBIT_QUEUE);
    await declareNotificationQueues(this.ch, RABBIT_NAIVE_QUEUE);

    const host = this.container.getHost();
    const mgmtPort = this.container.getMappedPort(15672);
    this.mgmtUrl = `http://${host}:${mgmtPort}`;
    await this.awaitManagementReady();
  }

  async reset(): Promise<void> {
    await this.drain();
  }

  async stop(): Promise<void> {
    if (this.ch) {
      await this.ch.close().catch(() => undefined);
    }
    if (this.conn) {
      await this.conn.close().catch(() => undefined);
    }
    if (this.container) {
      await this.container.stop();
    }
  }

  // publish seeds a job directly (a duplicate of a delivered one, or poison).
  async publish(job: NotificationJob, queue: string): Promise<void> {
    if (!this.ch) {
      throw new Error('RabbitMqHarness not started');
    }
    this.ch.sendToQueue(queue, serializeJob(job), { persistent: true, contentType: 'application/json' });
  }

  // readyCount is the real-time ready count via AMQP passive declare (mgmt lags).
  async readyCount(queue: string): Promise<number> {
    const ch = await this.connection.createChannel();
    try {
      const q = await ch.checkQueue(queue);
      return q.messageCount;
    } finally {
      await ch.close().catch(() => undefined);
    }
  }

  // awaitSettled blocks until a queue is fully settled: nothing ready AND nothing
  // in flight. Unacked is only visible via the management API (interval-refreshed),
  // so the condition must hold across TWO samples spaced wider than the 500 ms
  // stats interval.
  async awaitSettled(queue: string, deadlineMs = 30_000): Promise<void> {
    const until = Date.now() + deadlineMs;
    let settledSamples = 0;
    while (settledSamples < 2) {
      if (Date.now() > until) {
        throw new Error(`awaitSettled timed out for ${queue}`);
      }
      const ready = await this.readyCount(queue);
      const { unacked } = await this.queueStats(queue);
      if (ready === 0 && unacked === 0) {
        settledSamples++;
        if (settledSamples < 2) {
          await sleep(600);
        }
      } else {
        settledSamples = 0;
        await sleep(100);
      }
    }
  }

  // awaitDepth blocks until a (consumer-less, e.g. DLQ) queue holds exactly depth.
  async awaitDepth(queue: string, depth: number, deadlineMs = 30_000): Promise<void> {
    const until = Date.now() + deadlineMs;
    for (;;) {
      if (Date.now() > until) {
        throw new Error(`awaitDepth(${queue}, ${depth}) timed out`);
      }
      if ((await this.readyCount(queue)) === depth) {
        return;
      }
      await sleep(100);
    }
  }

  // drain purges everything ready then waits out anything in flight, across all
  // four queues (main + DLQ, real + naive).
  async drain(deadlineMs = 30_000): Promise<void> {
    const queues = [
      RABBIT_QUEUE,
      deadLetterQueue(RABBIT_QUEUE),
      RABBIT_NAIVE_QUEUE,
      deadLetterQueue(RABBIT_NAIVE_QUEUE),
    ];
    const until = Date.now() + deadlineMs;
    for (;;) {
      if (Date.now() > until) {
        throw new Error('drain timed out');
      }
      let settled = true;
      for (const queue of queues) {
        const ready = await this.readyCount(queue);
        const { unacked } = await this.queueStats(queue);
        if (ready > 0 && this.ch) {
          await this.ch.purgeQueue(queue);
        }
        settled = settled && ready === 0 && unacked === 0;
      }
      if (settled) {
        return;
      }
      await sleep(100);
    }
  }

  private async queueStats(queue: string): Promise<{ ready: number; unacked: number }> {
    const endpoint = `${this.mgmtUrl}/api/queues/%2F/${queue}`;
    const auth = Buffer.from(`${this.mgmtUser}:${this.mgmtPass}`).toString('base64');
    const resp = await fetch(endpoint, { headers: { Authorization: `Basic ${auth}` } });
    if (resp.status !== 200) {
      return { ready: 0, unacked: 0 };
    }
    const stats = (await resp.json()) as {
      messages_ready?: number;
      messages_unacknowledged?: number;
    };
    return { ready: stats.messages_ready ?? 0, unacked: stats.messages_unacknowledged ?? 0 };
  }

  private async awaitManagementReady(deadlineMs = 60_000): Promise<void> {
    const until = Date.now() + deadlineMs;
    const auth = Buffer.from(`${this.mgmtUser}:${this.mgmtPass}`).toString('base64');
    for (;;) {
      if (Date.now() > until) {
        throw new Error('rabbit management never became ready');
      }
      try {
        const resp = await fetch(`${this.mgmtUrl}/api/overview`, {
          headers: { Authorization: `Basic ${auth}` },
        });
        if (resp.status === 200) {
          return;
        }
      } catch {
        // not ready yet
      }
      await sleep(250);
    }
  }
}
