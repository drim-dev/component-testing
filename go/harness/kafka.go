package harness

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/infra"
	"github.com/moby/moby/client"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"
	"github.com/twmb/franz-go/pkg/kadm"
	"github.com/twmb/franz-go/pkg/kgo"
)

const (
	// Topic / GroupId are the suite's real fanout pair. The naive pair is parallel so a naive
	// consumer host never races the suite's correct consumer (§0.4).
	KafkaTopic      = "message-posted"
	KafkaGroup      = "feed-fanout"
	KafkaNaiveTopic = "message-posted-naive"
	KafkaNaiveGroup = "feed-fanout-naive"

	// kafkaBrokerPort is the host-facing PLAINTEXT listener inside the apache/kafka KRaft
	// container; testcontainers maps it to an ephemeral host port we advertise after start.
	kafkaBrokerPort = "9093"
	// kafkaStarterScript is the path the overridden entrypoint blocks on; the PostStart hook
	// writes it once the mapped host port is known (apache/kafka KRaft advertised-listener fix).
	kafkaStarterScript = "/tmp/relay_kafka_start.sh"
)

// KafkaHarness is the Kafka harness (async event log). Real single-node KRaft container.
// Seed = produce a crafted event directly (test the consumer in isolation); Assert =
// await-until on committed-offset == end-offset for the group (deterministic because the app
// commits only after the projector persisted its effects); never sleep. Fault control =
// pause/unpause the broker container, making "broker down" deterministic.
type KafkaHarness struct {
	container testcontainers.Container
	brokers   []string
	client    *kgo.Client // a produce/admin client owned by the harness
	admin     *kadm.Client
}

func (h *KafkaHarness) Brokers() []string { return h.brokers }

// Start brings up a single-node apache/kafka KRaft broker via a GenericContainer with the
// DIGEST-PINNED image (images.go). We do NOT use testcontainers-go's kafka module: that module
// picks its broker starter script by *detecting the image*, and a digest-pinned reference
// (apache/kafka:3.9.0@sha256:…) defeats the apache-vs-confluent detection, so it falls back to
// the confluent script which references /etc/confluent/docker/* — paths that do not exist in
// the apache/kafka image, leaving the container dead. Standing the broker up by hand keeps the
// LOCKED digest pin (04-dependencies.md §0.3) and the apache/kafka KRaft layout (/etc/kafka/*).
//
// The advertised-listener problem (Kafka must advertise the host-mapped port, unknown until
// after start) is solved exactly as the module solves it: the entrypoint blocks on a starter
// script we write from a PostStart hook once the mapped port is known.
func (h *KafkaHarness) Start(ctx context.Context) error {
	req := testcontainers.ContainerRequest{
		Image:        KafkaImage,
		ExposedPorts: []string{kafkaBrokerPort + "/tcp"},
		Env: map[string]string{
			"CLUSTER_ID":                     "relay-kraft-cluster-0",
			"KAFKA_NODE_ID":                  "1",
			"KAFKA_PROCESS_ROLES":            "broker,controller",
			"KAFKA_CONTROLLER_QUORUM_VOTERS": "1@localhost:9094",
			// Empty-host listener form (PLAINTEXT://:9092) binds all interfaces just like
			// 0.0.0.0, but the literal 0.0.0.0 trips KafkaConfig.validateValues during the
			// storage-format step ("advertised.listeners cannot use the nonroutable
			// meta-address 0.0.0.0") even when advertised.listeners is set explicitly.
			"KAFKA_LISTENERS":                                "PLAINTEXT://:9092,CONTROLLER://:9094,HOST://:" + kafkaBrokerPort,
			"KAFKA_LISTENER_SECURITY_PROTOCOL_MAP":           "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT,HOST:PLAINTEXT",
			"KAFKA_CONTROLLER_LISTENER_NAMES":                "CONTROLLER",
			"KAFKA_INTER_BROKER_LISTENER_NAME":               "PLAINTEXT",
			"KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR":         "1",
			"KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR": "1",
			"KAFKA_TRANSACTION_STATE_LOG_MIN_ISR":            "1",
			"KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS":         "0",
			// Placeholder; the PostStart hook rewrites HOST to the real mapped host port before
			// the broker actually starts (the entrypoint blocks until the starter script lands).
			"KAFKA_ADVERTISED_LISTENERS": "PLAINTEXT://localhost:9092,HOST://localhost:" + kafkaBrokerPort,
		},
		Entrypoint: []string{"sh", "-c"},
		Cmd: []string{
			"while [ ! -f " + kafkaStarterScript + " ]; do sleep 0.1; done; exec " + kafkaStarterScript,
		},
		WaitingFor: wait.ForLog("Kafka Server started").WithStartupTimeout(120 * time.Second),
		LifecycleHooks: []testcontainers.ContainerLifecycleHooks{{
			PostStarts: []testcontainers.ContainerHook{h.writeStarterScript},
		}},
	}

	container, err := testcontainers.GenericContainer(ctx, testcontainers.GenericContainerRequest{
		ContainerRequest: req,
		Started:          true,
	})
	if err != nil {
		return fmt.Errorf("start kafka: %w", err)
	}
	h.container = container

	host, err := container.Host(ctx)
	if err != nil {
		return fmt.Errorf("kafka host: %w", err)
	}
	mapped, err := container.MappedPort(ctx, kafkaBrokerPort+"/tcp")
	if err != nil {
		return fmt.Errorf("kafka mapped port: %w", err)
	}
	h.brokers = []string{fmt.Sprintf("%s:%s", host, mapped.Port())}

	client, err := kgo.NewClient(kgo.SeedBrokers(h.brokers...))
	if err != nil {
		return fmt.Errorf("kafka client: %w", err)
	}
	h.client = client
	h.admin = kadm.NewClient(client)

	if err := h.createTopics(ctx, KafkaTopic, KafkaNaiveTopic); err != nil {
		return fmt.Errorf("create topics: %w", err)
	}
	return nil
}

// writeStarterScript runs after the container is started but before the broker boots (the
// entrypoint blocks on the script file). It learns the host-mapped port, rewrites the HOST
// advertised listener to it, and hands off to the apache/kafka KRaft entrypoint at the image's
// own path (/etc/kafka/docker/run) — never the confluent path the module would have guessed.
func (h *KafkaHarness) writeStarterScript(ctx context.Context, c testcontainers.Container) error {
	host, err := c.Host(ctx)
	if err != nil {
		return err
	}
	mapped, err := c.MappedPort(ctx, kafkaBrokerPort+"/tcp")
	if err != nil {
		return err
	}
	advertised := fmt.Sprintf("PLAINTEXT://localhost:9092,HOST://%s:%s", host, mapped.Port())
	script := strings.Join([]string{
		"#!/bin/sh",
		"export KAFKA_ADVERTISED_LISTENERS='" + advertised + "'",
		"exec /etc/kafka/docker/run",
		"",
	}, "\n")
	return c.CopyToContainer(ctx, []byte(script), kafkaStarterScript, 0o755)
}

func (h *KafkaHarness) Reset(ctx context.Context) error {
	// Drain handled by the fixture (AwaitConsumed) before truncation; nothing topic-side to
	// reset between tests (offsets are monotonic; idempotency keys differ per test run).
	return nil
}

func (h *KafkaHarness) Stop(ctx context.Context) error {
	if h.client != nil {
		h.client.Close()
	}
	if h.container != nil {
		return h.container.Terminate(ctx)
	}
	return nil
}

// Publish seeds a crafted message.posted event directly to a topic.
func (h *KafkaHarness) Publish(ctx context.Context, ev domain.MessagePosted, topic string) error {
	record := &kgo.Record{Topic: topic, Key: []byte(ev.ChannelID), Value: infra.SerializeMessagePosted(ev)}
	return h.client.ProduceSync(ctx, record).FirstErr()
}

// AwaitConsumed blocks until the consumer group has committed everything published to a topic
// (committed offset >= end offset on partition 0). Because the app commits only after the
// projector persists, this implies every consumed event's effects are durable — the
// deterministic "settled" assertion (no bounded sleep).
func (h *KafkaHarness) AwaitConsumed(ctx context.Context, topic, group string) error {
	for {
		end, err := h.endOffset(ctx, topic)
		if err == nil && end >= 0 {
			if end == 0 {
				return nil
			}
			committed, cErr := h.committedOffset(ctx, topic, group)
			if cErr == nil && committed >= end {
				return nil
			}
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(100 * time.Millisecond):
		}
	}
}

// StopBroker pauses the broker container — produce requests then time out exactly as if it
// were gone (a stopped KRaft container trips over its formatted storage on restart; pausing
// freezes it and unpausing recovers deterministically). Sanctioned by 04-dependencies.md §3.
func (h *KafkaHarness) StopBroker(ctx context.Context) error {
	state, err := h.container.State(ctx)
	if err != nil {
		return err
	}
	if state.Paused {
		return nil
	}
	provider, err := testcontainers.NewDockerProvider()
	if err != nil {
		return err
	}
	defer func() { _ = provider.Close() }()
	_, err = provider.Client().ContainerPause(ctx, h.container.GetContainerID(), client.ContainerPauseOptions{})
	return err
}

// StartBroker unpauses the broker and blocks until it is ready AND the suite's consumer group
// is back to a stable assignment — so the next test never races the rejoin/rebalance (the
// zero-flake gate's key invariant).
func (h *KafkaHarness) StartBroker(ctx context.Context) error {
	provider, err := testcontainers.NewDockerProvider()
	if err != nil {
		return err
	}
	defer func() { _ = provider.Close() }()
	state, err := h.container.State(ctx)
	if err != nil {
		return err
	}
	if state.Paused {
		if _, err := provider.Client().ContainerUnpause(ctx, h.container.GetContainerID(), client.ContainerUnpauseOptions{}); err != nil {
			return err
		}
	}
	if err := h.awaitBrokerReady(ctx); err != nil {
		return err
	}
	return h.awaitGroupStable(ctx, KafkaGroup)
}

func (h *KafkaHarness) createTopics(ctx context.Context, topics ...string) error {
	// CreateTopics returns per-topic results; a TopicAlreadyExists on a re-run is fine, so a
	// request-level error is the only thing worth surfacing here.
	_, err := h.admin.CreateTopics(ctx, 1, 1, nil, topics...)
	return err
}

func (h *KafkaHarness) endOffset(ctx context.Context, topic string) (int64, error) {
	offsets, err := h.admin.ListEndOffsets(ctx, topic)
	if err != nil {
		return -1, err
	}
	o, ok := offsets.Lookup(topic, 0)
	if !ok {
		return -1, nil
	}
	return o.Offset, nil
}

func (h *KafkaHarness) committedOffset(ctx context.Context, topic, group string) (int64, error) {
	resp, err := h.admin.FetchOffsets(ctx, group)
	if err != nil {
		return -1, err
	}
	o, ok := resp.Lookup(topic, 0)
	if !ok {
		return -1, nil
	}
	return o.At, nil
}

func (h *KafkaHarness) awaitBrokerReady(ctx context.Context) error {
	for {
		_, err := h.admin.ListTopics(ctx, KafkaTopic)
		if err == nil {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(250 * time.Millisecond):
		}
	}
}

func (h *KafkaHarness) awaitGroupStable(ctx context.Context, group string) error {
	for {
		described, err := h.admin.DescribeGroups(ctx, group)
		if err == nil {
			if g, ok := described[group]; ok && g.State == "Stable" && len(g.Members) > 0 {
				return nil
			}
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(250 * time.Millisecond):
		}
	}
}

var _ DependencyHarness = (*KafkaHarness)(nil)
