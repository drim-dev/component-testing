// Relay's composition root. buildAppModule(infra) returns a Nest dynamic module
// that wires every CORRECT seam from the live infrastructure handles the harness
// passes in (the same containers the suite shares). There is no global state: the
// app is constructed from explicit Infra, and a test injects a naive variant by
// overriding exactly ONE provider token through Nest's testing DI
// (`overrideProvider(TOKEN)`) — the §11.D injection mechanism in the Nest idiom.

import { type DynamicModule, MiddlewareConsumer, Module, type NestModule } from '@nestjs/common';
import type { PrismaClient } from '@prisma/client';
import type { ChannelModel } from 'amqplib';
import type Redis from 'ioredis';
import type { Producer } from 'kafkajs';
import type { Client as MinioClient } from 'minio';

import { IdFactory } from '../idgen/idgen.js';
import { KafkaPublisher } from '../infra/kafka-infra.js';
import { HttpLinkPreviewer } from '../infra/link-previewer.js';
import { RabbitNotificationJobs } from '../infra/rabbit-infra.js';
import {
  RedisHeartbeats,
  RedisMembershipCache,
  RedisUnreadCounters,
} from '../infra/redis-infra.js';
import { S3Store } from '../infra/s3-infra.js';
import { GrpcPresenceClient } from '../presence/client.js';
import {
  ATTACHMENT_ACCESS,
  ATTACHMENT_STORE,
  CHANNEL_READ_GATE,
  CHANNEL_ROLE_GATE,
  CONVERSATION_WRITER,
  DM_ACCESS,
  FEED_PROJECTOR,
  HEARTBEATS,
  LINK_PREVIEWER,
  MEMBERSHIP_CACHE,
  MEMBERSHIP_WRITER,
  MESSAGE_POSTED_PUBLISHER,
  NOTIFICATION_JOBS,
  NOTIFICATION_RECORDER,
  PRESENCE_CLIENT,
  SUMMARIZER,
  SUMMARY_MODEL,
  UNREAD_COUNTERS,
  type MembershipCache,
  type SummaryModel,
  type UnreadCounters,
} from '../seams/seams.js';
import { Store } from '../store/store.js';
import { AttachmentsController } from './attachments.controller.js';
import { ChannelsController } from './channels.controller.js';
import { DmController } from './dm.controller.js';
import { FeedController } from './feed.controller.js';
import { IdentityMiddleware } from './identity.middleware.js';
import { LinkPreviewController } from './linkpreview.controller.js';
import { PresenceController } from './presence.controller.js';
import {
  CorrectAttachmentAccess,
  CorrectChannelReadGate,
  CorrectChannelRoleGate,
  CorrectConversationWriter,
  CorrectDmAccess,
  CorrectFeedProjector,
  CorrectMembershipWriter,
  CorrectNotificationRecorder,
  CorrectSummarizer,
  NotConfiguredSummaryModel,
} from './seams-impl.js';
import { SummaryController } from './summary.controller.js';
import { UsersController } from './users.controller.js';

// Infra is the set of live infrastructure handles the composition wires the
// CORRECT seams from. The harness builds these once from the shared containers;
// the LLM SummaryModel is optional (production leaves it unconfigured, the test
// harness passes its interaction-verifying fake).
export interface Infra {
  prisma: PrismaClient;
  redis: Redis;
  kafkaProducer: Producer;
  kafkaTopic: string;
  rabbit: ChannelModel;
  rabbitQueue: string;
  minio: MinioClient;
  presenceAddress: string;
  unfurlBaseUrl: string;
  generatorId: number;
  summaryModel?: SummaryModel;
}

@Module({})
export class AppModule implements NestModule {
  configure(consumer: MiddlewareConsumer): void {
    consumer.apply(IdentityMiddleware).forRoutes('*');
  }

  // build returns the configured dynamic module for the given infra.
  static build(infra: Infra): DynamicModule {
    const store = new Store(infra.prisma);
    const ids = new IdFactory(infra.generatorId);
    const cache: MembershipCache = new RedisMembershipCache(infra.redis);
    const unread: UnreadCounters = new RedisUnreadCounters(infra.redis);
    const summaryModel: SummaryModel = infra.summaryModel ?? new NotConfiguredSummaryModel();

    return {
      module: AppModule,
      controllers: [
        UsersController,
        DmController,
        ChannelsController,
        AttachmentsController,
        FeedController,
        PresenceController,
        SummaryController,
        LinkPreviewController,
      ],
      providers: [
        IdentityMiddleware,
        { provide: Store, useValue: store },
        { provide: IdFactory, useValue: ids },
        { provide: MEMBERSHIP_CACHE, useValue: cache },
        { provide: UNREAD_COUNTERS, useValue: unread },
        { provide: SUMMARY_MODEL, useValue: summaryModel },
        // Gallery seams (the injectable bugs) — CORRECT impls.
        { provide: DM_ACCESS, useValue: new CorrectDmAccess(store) },
        { provide: CONVERSATION_WRITER, useValue: new CorrectConversationWriter(store, ids) },
        { provide: CHANNEL_READ_GATE, useValue: new CorrectChannelReadGate(store) },
        { provide: CHANNEL_ROLE_GATE, useValue: new CorrectChannelRoleGate(store) },
        { provide: MEMBERSHIP_WRITER, useValue: new CorrectMembershipWriter(store, cache) },
        { provide: MESSAGE_POSTED_PUBLISHER, useValue: new KafkaPublisher(infra.kafkaProducer, infra.kafkaTopic) },
        { provide: FEED_PROJECTOR, useValue: new CorrectFeedProjector(store, unread, ids) },
        { provide: NOTIFICATION_RECORDER, useValue: new CorrectNotificationRecorder(store, ids) },
        { provide: PRESENCE_CLIENT, useValue: new GrpcPresenceClient(infra.presenceAddress) },
        { provide: LINK_PREVIEWER, useValue: new HttpLinkPreviewer(infra.redis, infra.unfurlBaseUrl) },
        { provide: ATTACHMENT_ACCESS, useValue: new CorrectAttachmentAccess(store) },
        // Infrastructure ports.
        { provide: NOTIFICATION_JOBS, useValue: new RabbitNotificationJobs(infra.rabbit, infra.rabbitQueue) },
        { provide: ATTACHMENT_STORE, useValue: new S3Store(infra.minio) },
        { provide: SUMMARIZER, useValue: new CorrectSummarizer(summaryModel) },
        { provide: HEARTBEATS, useValue: new RedisHeartbeats(infra.redis) },
      ],
    };
  }
}
