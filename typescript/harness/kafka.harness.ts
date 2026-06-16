// The Kafka harness (async event log). Real single-node KRaft container.
// Seed = produce a crafted event directly (test the consumer in isolation);
// Assert = await-until on committed-offset == end-offset for the group (never
// sleep). Fault control = pause/unpause the broker container, making "broker
// down" deterministic.
//
// DIGEST PRESERVED (the LOCKED decision). We do NOT use a testcontainers kafka
// module: the modules pick their broker starter script by *detecting the image*,
// and a digest-pinned reference (apache/kafka:3.9.0@sha256:…) defeats the
// detection, so they fall back to the confluent script (/etc/confluent/docker/*)
// — paths that do not exist in the apache/kafka image, leaving the container
// dead. So we stand the broker up by hand via a GenericContainer with the
// digest-pinned image, a single-node KRaft env, and the
// advertised-listener-via-starter-script technique the Go pilot proved: the
// entrypoint blocks on a script we copy in once the host port is mapped.

import { Admin, Kafka, logLevel, type Producer } from 'kafkajs';
import { GenericContainer, type StartedTestContainer, Wait } from 'testcontainers';

import type { MessagePosted } from '../src/domain/domain.js';
import { serializeMessagePosted } from '../src/infra/kafka-infra.js';
import type { DependencyHarness } from './dependency-harness.js';
import { KAFKA_IMAGE } from './images.js';

// The suite's real fanout pair. The naive pair is parallel so a naive consumer
// host never races the suite's correct consumer (§0.4).
export const KAFKA_TOPIC = 'message-posted';
export const KAFKA_GROUP = 'feed-fanout';
export const KAFKA_NAIVE_TOPIC = 'message-posted-naive';
export const KAFKA_NAIVE_GROUP = 'feed-fanout-naive';

// kafkaBrokerPort is the host-facing PLAINTEXT (HOST) listener inside the
// container; testcontainers maps it to an ephemeral host port we advertise after
// start.
const KAFKA_BROKER_PORT = 9093;
const STARTER_SCRIPT = '/tmp/relay_kafka_start.sh';
const READY_SENTINEL = 'relay-kafka-entrypoint-ready';

const sleep = (ms: number): Promise<void> => new Promise((r) => setTimeout(r, ms));

export class KafkaHarness implements DependencyHarness {
  private container?: StartedTestContainer;
  private kafka?: Kafka;
  private admin?: Admin;
  private brokerList: string[] = [];
  private paused = false;

  get brokers(): string[] {
    return this.brokerList;
  }

  async start(): Promise<void> {
    this.container = await new GenericContainer(KAFKA_IMAGE)
      .withExposedPorts(KAFKA_BROKER_PORT)
      .withEnvironment({
        CLUSTER_ID: 'relay-kraft-cluster-0',
        KAFKA_NODE_ID: '1',
        KAFKA_PROCESS_ROLES: 'broker,controller',
        KAFKA_CONTROLLER_QUORUM_VOTERS: '1@localhost:9094',
        // Empty-host listener form (PLAINTEXT://:9092) binds all interfaces just
        // like 0.0.0.0, but the literal 0.0.0.0 trips KafkaConfig.validateValues
        // during the storage-format step even when advertised.listeners is set.
        KAFKA_LISTENERS: `PLAINTEXT://:9092,CONTROLLER://:9094,HOST://:${KAFKA_BROKER_PORT}`,
        KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: 'PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT,HOST:PLAINTEXT',
        KAFKA_CONTROLLER_LISTENER_NAMES: 'CONTROLLER',
        KAFKA_INTER_BROKER_LISTENER_NAME: 'PLAINTEXT',
        KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: '1',
        KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: '1',
        KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: '1',
        KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: '0',
        // Placeholder; the starter script rewrites HOST to the real mapped host
        // port before the broker actually starts.
        KAFKA_ADVERTISED_LISTENERS: `PLAINTEXT://localhost:9092,HOST://localhost:${KAFKA_BROKER_PORT}`,
      })
      .withEntrypoint(['sh', '-c'])
      // The entrypoint prints a sentinel (so start() can return once the shell is
      // up — the broker has NOT started yet), then blocks on the starter script we
      // copy in with the real mapped port, then hands off to the apache/kafka KRaft
      // entrypoint at the image's own path (/etc/kafka/docker/run), never the
      // confluent path a module would have guessed.
      .withCommand([
        `echo ${READY_SENTINEL}; while [ ! -f ${STARTER_SCRIPT} ]; do sleep 0.1; done; exec ${STARTER_SCRIPT}`,
      ])
      .withWaitStrategy(Wait.forLogMessage(READY_SENTINEL).withStartupTimeout(120_000))
      .start();

    const host = this.container.getHost();
    const mapped = this.container.getMappedPort(KAFKA_BROKER_PORT);
    const advertised = `PLAINTEXT://localhost:9092,HOST://${host}:${mapped}`;
    const script = ['#!/bin/sh', `export KAFKA_ADVERTISED_LISTENERS='${advertised}'`, 'exec /etc/kafka/docker/run', ''].join('\n');
    await this.container.copyContentToContainer([{ content: script, target: STARTER_SCRIPT, mode: 0o755 }]);

    this.brokerList = [`${host}:${mapped}`];
    // logLevel NOTHING: the broker-down / rebalance reconnect chatter kafkajs
    // emits during the G-KAFKA pause/unpause probes is expected infra noise, not
    // a test signal — silence it so the suite output stays pristine.
    this.kafka = new Kafka({
      brokers: this.brokerList,
      retry: { retries: 8, initialRetryTime: 200 },
      logLevel: logLevel.NOTHING,
    });
    this.admin = this.kafka.admin();
    await this.awaitBrokerReady();
    await this.admin.connect();
    await this.admin.createTopics({
      topics: [
        { topic: KAFKA_TOPIC, numPartitions: 1, replicationFactor: 1 },
        { topic: KAFKA_NAIVE_TOPIC, numPartitions: 1, replicationFactor: 1 },
      ],
      waitForLeaders: true,
    });
  }

  reset(): Promise<void> {
    // Drain handled by the fixture (awaitConsumed) before truncation; nothing
    // topic-side to reset between tests (offsets are monotonic; idempotency keys
    // differ per test run).
    return Promise.resolve();
  }

  async stop(): Promise<void> {
    if (this.admin) {
      await this.admin.disconnect().catch(() => undefined);
    }
    if (this.container) {
      await this.container.stop();
    }
  }

  // newProducer returns a connected producer the fixture wires into the app (and
  // the G-KAFKA-producer naive variant uses to fire-and-forget).
  async newProducer(): Promise<Producer> {
    if (!this.kafka) {
      throw new Error('KafkaHarness not started');
    }
    const producer = this.kafka.producer({ allowAutoTopicCreation: false });
    await producer.connect();
    return producer;
  }

  client(): Kafka {
    if (!this.kafka) {
      throw new Error('KafkaHarness not started');
    }
    return this.kafka;
  }

  // publish seeds a crafted message.posted event directly to a topic.
  async publish(ev: MessagePosted, topic: string): Promise<void> {
    const producer = await this.newProducer();
    try {
      await producer.send({ topic, messages: [{ key: ev.channelId, value: serializeMessagePosted(ev) }], acks: -1 });
    } finally {
      await producer.disconnect();
    }
  }

  // awaitConsumed blocks until the consumer group has committed everything
  // published to a topic (committed offset >= end offset on partition 0). Because
  // the app commits only after the projector persists, this implies every consumed
  // event's effects are durable — the deterministic "settled" assertion.
  async awaitConsumed(topic: string, group: string, deadlineMs = 30_000): Promise<void> {
    const admin = this.requireAdmin();
    const until = Date.now() + deadlineMs;
    for (;;) {
      if (Date.now() > until) {
        throw new Error(`awaitConsumed(${topic}, ${group}) timed out`);
      }
      const end = await this.endOffset(topic);
      if (end === 0n) {
        return;
      }
      if (end >= 0n) {
        const committed = await this.committedOffset(admin, topic, group);
        if (committed >= end) {
          return;
        }
      }
      await sleep(100);
    }
  }

  // stopBroker pauses the broker container — produce requests then time out
  // exactly as if it were gone. Sanctioned by 04-dependencies.md §3.
  async stopBroker(): Promise<void> {
    if (this.paused || !this.container) {
      return;
    }
    const docker = await getDockerode();
    await docker.getContainer(this.container.getId()).pause();
    this.paused = true;
  }

  // startBroker unpauses the broker and blocks until it is ready AND the suite's
  // consumer group is back to a stable assignment — so the next test never races
  // the rejoin/rebalance (the zero-flake gate's key invariant).
  async startBroker(): Promise<void> {
    if (!this.paused || !this.container) {
      return;
    }
    const docker = await getDockerode();
    await docker.getContainer(this.container.getId()).unpause();
    this.paused = false;
    await this.awaitBrokerReady();
    await this.awaitGroupStable(KAFKA_GROUP);
  }

  private requireAdmin(): Admin {
    if (!this.admin) {
      throw new Error('KafkaHarness not started');
    }
    return this.admin;
  }

  private async endOffset(topic: string): Promise<bigint> {
    try {
      const offsets = await this.requireAdmin().fetchTopicOffsets(topic);
      const p0 = offsets.find((o) => o.partition === 0);
      return p0 ? BigInt(p0.high) : -1n;
    } catch {
      return -1n;
    }
  }

  private async committedOffset(admin: Admin, topic: string, group: string): Promise<bigint> {
    try {
      const result = await admin.fetchOffsets({ groupId: group, topics: [topic] });
      const topicEntry = result.find((t) => t.topic === topic);
      const p0 = topicEntry?.partitions.find((p) => p.partition === 0);
      if (!p0 || p0.offset === '-1') {
        return -1n;
      }
      return BigInt(p0.offset);
    } catch {
      return -1n;
    }
  }

  private async awaitBrokerReady(deadlineMs = 60_000): Promise<void> {
    const admin = this.requireAdmin();
    const until = Date.now() + deadlineMs;
    for (;;) {
      if (Date.now() > until) {
        throw new Error('kafka broker never became ready');
      }
      try {
        await admin.connect();
        await admin.listTopics();
        return;
      } catch {
        await admin.disconnect().catch(() => undefined);
        await sleep(250);
      }
    }
  }

  private async awaitGroupStable(group: string, deadlineMs = 30_000): Promise<void> {
    const admin = this.requireAdmin();
    const until = Date.now() + deadlineMs;
    for (;;) {
      if (Date.now() > until) {
        throw new Error(`kafka group ${group} never became stable`);
      }
      try {
        const described = await admin.describeGroups([group]);
        const g = described.groups.find((x) => x.groupId === group);
        if (g?.state === 'Stable' && g.members.length > 0) {
          return;
        }
      } catch {
        // broker still recovering
      }
      await sleep(250);
    }
  }
}

// getDockerode reaches the same Docker daemon testcontainers uses, to pause /
// unpause the broker container (the deterministic "broker down" probe).
async function getDockerode(): Promise<import('dockerode')> {
  const { getContainerRuntimeClient } = await import('testcontainers');
  const client = await getContainerRuntimeClient();
  return client.container.dockerode;
}
