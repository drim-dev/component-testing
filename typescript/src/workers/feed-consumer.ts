// The Kafka feed-fanout consumer. Delivery is at-least-once and the projector is
// idempotent (G-KAFKA consumer); the offset is committed only AFTER apply
// succeeds, so a processing failure is retried (never silently skipped) and the
// harness's await-idle assertion ("committed >= end") implies the effects are
// durable.

import type { Consumer } from 'kafkajs';

import { deserializeMessagePosted } from '../infra/kafka-infra.js';
import type { FeedProjector } from '../seams/seams.js';

export class FeedConsumer {
  constructor(
    private readonly consumer: Consumer,
    private readonly projector: FeedProjector,
  ) {}

  async run(topic: string): Promise<void> {
    await this.consumer.subscribe({ topic, fromBeginning: true });
    await this.consumer.run({
      autoCommit: false,
      eachMessage: async ({ topic: t, partition, message }) => {
        if (!message.value) {
          return;
        }
        const ev = deserializeMessagePosted(message.value);
        // The projector's idempotency makes a retry safe; commit only on success.
        await this.projector.apply(ev);
        await this.consumer.commitOffsets([
          { topic: t, partition, offset: (Number(message.offset) + 1).toString() },
        ]);
      },
    });
  }
}
