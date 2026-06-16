// RabbitMQ port: the DM notification-job publisher (queues / acks / DLQ — a
// different shape from Kafka's log/offsets). The publish is confirmed (the
// pinned post-commit publish; a failure after commit → 500). The queue topology
// (quorum + DLX → DLQ) is declared identically by the publisher, the worker, and
// the harness — a mismatched redeclare is a channel error.

import type { Channel, ChannelModel, ConfirmChannel } from 'amqplib';

import type { NotificationJob } from '../domain/domain.js';
import type { NotificationJobs } from '../seams/seams.js';

export const NOTIFY_QUEUE = 'notify.dm';

export function deadLetterQueue(queue: string): string {
  return `${queue}.dlq`;
}

// declareNotificationQueues declares the quorum queue + its DLQ with the SAME
// arguments everywhere. x-delivery-limit is a broker-side backstop only (it
// counts dead-letter republishes, not requeued nacks), so the worker enforces
// the attempt cap itself.
export async function declareNotificationQueues(ch: Channel, queue: string): Promise<void> {
  const dlq = deadLetterQueue(queue);
  await ch.assertQueue(dlq, { durable: true, arguments: { 'x-queue-type': 'quorum' } });
  await ch.assertQueue(queue, {
    durable: true,
    arguments: {
      'x-queue-type': 'quorum',
      'x-delivery-limit': 2,
      'x-dead-letter-exchange': '',
      'x-dead-letter-routing-key': dlq,
    },
  });
}

export function serializeJob(job: NotificationJob): Buffer {
  return Buffer.from(JSON.stringify(job));
}

export function deserializeJob(b: Buffer): NotificationJob {
  return JSON.parse(b.toString()) as NotificationJob;
}

export class RabbitNotificationJobs implements NotificationJobs {
  constructor(
    private readonly connection: ChannelModel,
    private readonly queue: string,
  ) {}

  async enqueue(job: NotificationJob): Promise<void> {
    const ch: ConfirmChannel = await this.connection.createConfirmChannel();
    try {
      await declareNotificationQueues(ch, this.queue);
      await new Promise<void>((resolve, reject) => {
        ch.sendToQueue(
          this.queue,
          serializeJob(job),
          { persistent: true, contentType: 'application/json' },
          (err) => (err ? reject(err instanceof Error ? err : new Error(String(err))) : resolve()),
        );
      });
    } finally {
      await ch.close();
    }
  }
}
