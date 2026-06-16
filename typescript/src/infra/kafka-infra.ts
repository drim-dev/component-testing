// Kafka producer port: the correct G-KAFKA producer seam AWAITS broker
// confirmation. If the broker is unavailable the synchronous send rejects, the
// caller rolls back the message transaction, and the API answers 503 — never
// fire-and-forget. The publish is bounded so a down broker surfaces the pinned
// 503 promptly and deterministically (the zero-flake gate), not a hang.

import type { Producer } from 'kafkajs';

import { unavailable } from '../apierr/apierr.js';
import type { MessagePosted } from '../domain/domain.js';
import type { MessagePostedPublisher } from '../seams/seams.js';

export const MESSAGE_POSTED_TOPIC = 'message-posted';

// publishConfirmTimeout bounds how long a post waits for broker confirmation. A
// reachable broker acks in milliseconds; a down broker must surface as 503
// promptly, never hang on the producer's internal retries.
const PUBLISH_CONFIRM_TIMEOUT_MS = 3_000;

export function serializeMessagePosted(ev: MessagePosted): Buffer {
  return Buffer.from(JSON.stringify(ev));
}

export function deserializeMessagePosted(b: Buffer): MessagePosted {
  return JSON.parse(b.toString()) as MessagePosted;
}

export class KafkaPublisher implements MessagePostedPublisher {
  constructor(
    private readonly producer: Producer,
    private readonly topic: string,
  ) {}

  async publish(ev: MessagePosted): Promise<void> {
    const send = this.producer.send({
      topic: this.topic,
      messages: [{ key: ev.channelId, value: serializeMessagePosted(ev) }],
      // Wait for the leader (acks: -1 = all in-sync replicas; single node here).
      acks: -1,
    });
    // Bound the confirm: a paused broker must surface as 503 in ~3 s, not hang on
    // kafkajs's reconnection backoff. The lost producer record is reaped on the
    // rollback (nothing is committed).
    try {
      await withTimeout(send, PUBLISH_CONFIRM_TIMEOUT_MS);
    } catch {
      throw unavailable('events:unavailable', 'The event broker is unavailable.');
    }
  }
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('publish confirm timeout')), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (err: unknown) => {
        clearTimeout(timer);
        reject(err instanceof Error ? err : new Error(String(err)));
      },
    );
  });
}
