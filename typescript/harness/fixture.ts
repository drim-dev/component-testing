// Fixture composes all dependency harnesses for the suite (one Docker host, one
// suite) and builds the assembled Relay app against the real containers. It is
// the TestFixture-style composition the spec calls for; extensibility = add a
// harness field, not runtime re-composition (honest framing —
// 04-dependencies.md §9).

import type { Server } from 'node:http';
import type { AddressInfo } from 'node:net';

import { type INestApplication } from '@nestjs/common';
import { NestFactory } from '@nestjs/core';
import type { Consumer, Producer } from 'kafkajs';

import { AppModule, type Infra } from '../src/app/app.module.js';
import { ErrorFilter } from '../src/app/error.filter.js';
import { Store } from '../src/store/store.js';
import { DatabaseHarness } from './database.harness.js';
import { KafkaHarness, KAFKA_GROUP, KAFKA_TOPIC } from './kafka.harness.js';
import { LlmHarness } from './llm.harness.js';
import { PresenceHarness } from './presence.harness.js';
import { RabbitMqHarness, RABBIT_QUEUE } from './rabbitmq.harness.js';
import { RedisHarness } from './redis.harness.js';
import { S3Harness } from './s3.harness.js';
import { UnfurlHarness } from './unfurl.harness.js';
import { FeedConsumer } from '../src/workers/feed-consumer.js';
import { NotificationWorker } from '../src/workers/notification-worker.js';
import { CorrectFeedProjector, CorrectNotificationRecorder } from '../src/app/seams-impl.js';
import { IdFactory } from '../src/idgen/idgen.js';
import { RedisUnreadCounters } from '../src/infra/redis-infra.js';

export class Fixture {
  readonly database = new DatabaseHarness();
  readonly redis = new RedisHarness();
  readonly kafka = new KafkaHarness();
  readonly rabbit = new RabbitMqHarness();
  readonly s3 = new S3Harness();
  readonly llm = new LlmHarness();
  readonly unfurl = new UnfurlHarness();
  presence!: PresenceHarness;

  store!: Store;

  private app?: INestApplication;
  private appUrl = '';
  private appProducer?: Producer;
  private feedConsumer?: Consumer;
  private feedWorker?: FeedConsumer;
  private notifyWorker?: NotificationWorker;

  baseUrl(): string {
    return this.appUrl;
  }

  async start(): Promise<void> {
    // Real deps; kept sequential for clear failure messages (one Docker host).
    await this.database.start();
    await this.redis.start();
    await this.kafka.start();
    await this.rabbit.start();
    await this.s3.start();
    await this.llm.start();
    await this.unfurl.start();
    // Presence depends on Redis (shares the connection), so it starts after.
    this.presence = new PresenceHarness(this.redis.address);
    await this.presence.start();

    this.store = new Store(this.database.prisma);
    this.appProducer = await this.kafka.newProducer();

    const infra = this.buildInfra(0, this.appProducer);
    this.app = await NestFactory.create(AppModule.build(infra), { logger: false });
    this.app.useGlobalFilters(new ErrorFilter());
    await this.app.listen(0, '127.0.0.1');
    const server = this.app.getHttpServer() as Server;
    const addr = server.address() as AddressInfo;
    this.appUrl = `http://127.0.0.1:${addr.port}`;

    await this.startWorkers();
  }

  // buildInfra assembles the live infrastructure handles the composition root
  // wires the CORRECT seams from. generatorId distinguishes the suite's default
  // id space (0) from a naive host's (1).
  private buildInfra(generatorId: number, producer: Producer): Infra {
    return {
      prisma: this.database.prisma,
      redis: this.redis.client,
      kafkaProducer: producer,
      kafkaTopic: KAFKA_TOPIC,
      rabbit: this.rabbit.connection,
      rabbitQueue: RABBIT_QUEUE,
      minio: this.s3.client,
      presenceAddress: this.presence.address,
      unfurlBaseUrl: this.unfurl.getBaseUrl(),
      generatorId,
      summaryModel: this.llm.model(),
    };
  }

  private async startWorkers(): Promise<void> {
    const ids = new IdFactory(0);
    const unread = new RedisUnreadCounters(this.redis.client);
    const projector = new CorrectFeedProjector(this.store, unread, ids);
    const recorder = new CorrectNotificationRecorder(this.store, ids);

    this.feedConsumer = this.kafka.client().consumer({ groupId: KAFKA_GROUP });
    await this.feedConsumer.connect();
    this.feedWorker = new FeedConsumer(this.feedConsumer, projector);
    await this.feedWorker.run(KAFKA_TOPIC);

    this.notifyWorker = new NotificationWorker(this.rabbit.connection, recorder, RABBIT_QUEUE);
    await this.notifyWorker.run();
  }

  // reset returns every dependency to a clean state between tests. Brokers are
  // drained BEFORE the DB truncate so a late event/job never writes into the next
  // test's clean state.
  async reset(): Promise<void> {
    await this.kafka.awaitConsumed(KAFKA_TOPIC, KAFKA_GROUP);
    await this.rabbit.drain();
    await this.database.reset();
    await this.redis.reset();
    await this.s3.reset();
    await this.llm.reset();
    await this.unfurl.reset();
    await this.presence.reset();
  }

  async stop(): Promise<void> {
    if (this.notifyWorker) {
      await this.notifyWorker.stop();
    }
    if (this.feedConsumer) {
      await this.feedConsumer.disconnect().catch(() => undefined);
    }
    if (this.appProducer) {
      await this.appProducer.disconnect().catch(() => undefined);
    }
    if (this.app) {
      await this.app.close();
    }
    await this.presence.stop();
    await this.unfurl.stop();
    await this.llm.stop();
    await this.s3.stop();
    await this.rabbit.stop();
    await this.kafka.stop();
    await this.redis.stop();
    await this.database.stop();
  }

  // ---- naive-variant injection (the §11.D mechanism in the Nest idiom) ----
  // The NaiveApp/NaiveWorkers helpers live in naive-app.ts to keep this file the
  // pure composition; they reach these accessors.

  async newProducer(): Promise<Producer> {
    return this.kafka.newProducer();
  }

  infraFor(generatorId: number, producer: Producer): Infra {
    return this.buildInfra(generatorId, producer);
  }
}
