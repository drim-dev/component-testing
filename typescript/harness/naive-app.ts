// The §11.D injection mechanism in the Nest idiom — the seam swap rides Nest's
// DI container. NaiveApp builds a SECOND Relay app from the same live
// infrastructure (the shared containers), overriding exactly ONE provider token
// with its naive variant via the testing module's `overrideProvider(TOKEN)`.
// Because every gallery seam is a DI token the handlers depend on, "swap one
// seam" is one `.overrideProvider(TOKEN).useValue(naive)` call — the same seam
// the harness already uses, expressed in Nest's idiom (DI override), the clean
// analog of .NET's RemoveAll/re-register and Go's struct-field swap.
//
// The naive app runs on a DISTINCT IdFactory generator (id 1) so its ids never
// collide with data seeded from the default generator (id 0). Its workers are
// OFF — a naive API host must not consume the suite's real topics/queues.

import type { Server } from 'node:http';
import type { AddressInfo } from 'node:net';

import { type INestApplication } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import type { Consumer, Producer } from 'kafkajs';

import { AppModule } from '../src/app/app.module.js';
import { ErrorFilter } from '../src/app/error.filter.js';
import { CorrectFeedProjector, CorrectNotificationRecorder } from '../src/app/seams-impl.js';
import { IdFactory } from '../src/idgen/idgen.js';
import { RedisUnreadCounters } from '../src/infra/redis-infra.js';
import type { FeedProjector, NotificationRecorder } from '../src/seams/seams.js';
import { FeedConsumer } from '../src/workers/feed-consumer.js';
import { NotificationWorker } from '../src/workers/notification-worker.js';
import type { Fixture } from './fixture.js';
import {
  KAFKA_NAIVE_GROUP,
  KAFKA_NAIVE_TOPIC,
} from './kafka.harness.js';
import { RABBIT_NAIVE_QUEUE } from './rabbitmq.harness.js';

// A single provider override: the DI token and the naive value to bind to it.
export interface SeamOverride {
  token: symbol;
  value: unknown;
}

// NaiveAppHandle is a disposable naive API host (a real listening Nest app).
export class NaiveAppHandle {
  constructor(
    private readonly app: INestApplication,
    private readonly url: string,
    private readonly producer: Producer,
  ) {}

  baseUrl(): string {
    return this.url;
  }

  async close(): Promise<void> {
    await this.app.close();
    await this.producer.disconnect().catch(() => undefined);
  }
}

// naiveApp derives a one-off app whose listed seams are replaced by their naive
// variants — one DI override each, nothing else changed.
export async function naiveApp(fixture: Fixture, overrides: SeamOverride[]): Promise<NaiveAppHandle> {
  const producer = await fixture.newProducer();
  const infra = fixture.infraFor(1, producer);
  // AppModule.build returns a DynamicModule; Test.createTestingModule takes module
  // metadata, so the dynamic module is IMPORTED. overrideProvider then reaches the
  // gallery seam tokens it exports — the single-provider swap, the Nest idiom.
  let builder = Test.createTestingModule({ imports: [AppModule.build(infra)] });
  for (const o of overrides) {
    builder = builder.overrideProvider(o.token).useValue(o.value);
  }
  const moduleRef = await builder.compile();
  const app = moduleRef.createNestApplication({ logger: false });
  app.useGlobalFilters(new ErrorFilter());
  await app.init();
  await app.listen(0, '127.0.0.1');
  const server = app.getHttpServer() as Server;
  const addr = server.address() as AddressInfo;
  return new NaiveAppHandle(app, `http://127.0.0.1:${addr.port}`, producer);
}

// NaiveWorkerHandle is a disposable naive consumer host (G-KAFKA consumer /
// G-RABBIT) pointed at the PARALLEL topic/group/queue, so its deliberately-buggy
// processing never races the suite's correct consumer.
export class NaiveWorkerHandle {
  constructor(
    private readonly consumer: Consumer,
    private readonly notify: NotificationWorker,
  ) {}

  async close(): Promise<void> {
    await this.notify.stop();
    await this.consumer.disconnect().catch(() => undefined);
  }
}

// naiveWorkers starts a naive consumer-side host on the parallel topic/queue. The
// caller supplies whichever naive variant it is demonstrating (feed projector
// and/or notification recorder); the other runs correct.
export async function naiveWorkers(
  fixture: Fixture,
  build: (correct: { projector: FeedProjector; recorder: NotificationRecorder }) => {
    projector: FeedProjector;
    recorder: NotificationRecorder;
  },
): Promise<NaiveWorkerHandle> {
  const ids = new IdFactory(1);
  const unread = new RedisUnreadCounters(fixture.redis.client);
  const correctProjector = new CorrectFeedProjector(fixture.store, unread, ids);
  const correctRecorder = new CorrectNotificationRecorder(fixture.store, ids);
  const { projector, recorder } = build({ projector: correctProjector, recorder: correctRecorder });

  const consumer = fixture.kafka.client().consumer({ groupId: KAFKA_NAIVE_GROUP });
  await consumer.connect();
  const feed = new FeedConsumer(consumer, projector);
  await feed.run(KAFKA_NAIVE_TOPIC);

  const notify = new NotificationWorker(fixture.rabbit.connection, recorder, RABBIT_NAIVE_QUEUE);
  await notify.run();

  return new NaiveWorkerHandle(consumer, notify);
}
