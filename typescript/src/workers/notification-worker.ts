// The RabbitMQ notification worker. It consumes notify.dm with manual acks,
// prefetch 1: a job is acked only after the recorder persists its effect. A
// failing job is retried up to MAX_ATTEMPTS, then dead-lettered (a final
// requeue:false nack routes it to the DLX → DLQ deterministically), so the queue
// keeps flowing past a poison job.
//
// The attempt cap is enforced HERE via the redelivery count, NOT by leaning on
// the broker's x-delivery-limit (which counts dead-letter republishes, not
// requeued nacks, so it would loop). A quorum queue stamps x-delivery-count =
// prior deliveries on a requeued message (absent on first delivery). The correct
// recorder treats a redelivered duplicate as success → ack, so a duplicate never
// crash-loops; the naive variant does not, so a redelivered duplicate
// dead-letters after MAX_ATTEMPTS.

import type { Channel, ChannelModel, ConsumeMessage } from 'amqplib';

import { declareNotificationQueues, deserializeJob } from '../infra/rabbit-infra.js';
import type { NotificationRecorder } from '../seams/seams.js';

export const MAX_ATTEMPTS = 3;

export class NotificationWorker {
  private channel: Channel | null = null;

  constructor(
    private readonly connection: ChannelModel,
    private readonly recorder: NotificationRecorder,
    private readonly queue: string,
  ) {}

  async run(): Promise<void> {
    const ch = await this.connection.createChannel();
    this.channel = ch;
    await declareNotificationQueues(ch, this.queue);
    await ch.prefetch(1);
    await ch.consume(this.queue, (msg: ConsumeMessage | null) => {
      if (msg) {
        void this.handle(ch, msg);
      }
    });
  }

  async stop(): Promise<void> {
    if (this.channel) {
      await this.channel.close().catch(() => undefined);
      this.channel = null;
    }
  }

  private async handle(ch: Channel, msg: ConsumeMessage): Promise<void> {
    try {
      const job = deserializeJob(msg.content);
      await this.recorder.record(job);
      ch.ack(msg);
    } catch {
      const exhausted = deliveryCount(msg) >= MAX_ATTEMPTS;
      // On the final attempt nack with requeue:false → DLX → DLQ; else requeue.
      ch.nack(msg, false, !exhausted);
    }
  }
}

// deliveryCount returns this delivery attempt (1-based). A RabbitMQ quorum queue
// stamps x-acquired-count = prior acquisitions on a requeued nack (absent on the
// first delivery), so this attempt is that count + 1.
function deliveryCount(msg: ConsumeMessage): number {
  const raw: unknown = msg.properties.headers?.['x-acquired-count'];
  if (typeof raw === 'number') {
    return raw + 1;
  }
  return 1;
}
