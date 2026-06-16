package dev.drim.relay.harness;

import dev.drim.relay.domain.Events;
import dev.drim.relay.infra.EventCodecs;
import dev.drim.relay.infra.KafkaMessagePostedPublisher;
import dev.drim.relay.infra.MessagePostedCodec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.ConsumerGroupState;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * The Kafka harness (async event log). Real single-node KRaft container via {@code
 * GenericContainer} with the DIGEST-PINNED image — NOT Testcontainers' KafkaContainer: that module
 * picks its broker starter script by DETECTING the image, and a digest-pinned reference defeats the
 * apache-vs-confluent detection, so it falls back to the confluent script ({@code
 * /etc/confluent/docker/*}) — paths that do not exist in the apache/kafka image, leaving the
 * container dead. Standing the broker up by hand keeps the LOCKED digest pin and the apache/kafka
 * KRaft layout ({@code /etc/kafka/docker/run}). Mirrors go/harness/kafka.go.
 *
 * <p>Seed = produce a crafted event directly (test the consumer in isolation); Assert = await-until
 * committed-offset == end-offset for the group (deterministic because the app commits only after
 * the projector persists); never sleep. Fault control = pause/unpause the broker container.
 */
public final class KafkaHarness implements DependencyHarness {
  public static final String TOPIC = KafkaMessagePostedPublisher.TOPIC;
  public static final String GROUP = "feed-fanout";
  public static final String NAIVE_TOPIC = "message-posted-naive";
  public static final String NAIVE_GROUP = "feed-fanout-naive";

  private static final int BROKER_PORT = 9093;
  private static final String STARTER_SCRIPT = "/tmp/relay_kafka_start.sh";

  private final MessagePostedCodec codec = new MessagePostedCodec(EventCodecs.canonicalMapper());

  private final KraftContainer container =
      new KraftContainer(this)
          .withExposedPorts(BROKER_PORT)
          .withEnv(brokerEnv())
          .withCreateContainerCmdModifier(
              cmd ->
                  cmd.withEntrypoint("sh", "-c")
                      .withCmd(
                          "while [ ! -f "
                              + STARTER_SCRIPT
                              + " ]; do sleep 0.1; done; exec "
                              + STARTER_SCRIPT))
          .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1));

  /**
   * A GenericContainer that writes the advertised-listener starter script in {@code
   * containerIsStarting} — which fires AFTER the container process is up (so the mapped host port
   * is known) but BEFORE the waitingFor log gate completes. The entrypoint blocks on the script's
   * existence, so "Kafka Server started" only appears once we write it: the hook breaks what would
   * otherwise be a deadlock between start() (waiting on the log) and writing the script.
   */
  static final class KraftContainer extends GenericContainer<KraftContainer> {
    private final KafkaHarness harness;

    KraftContainer(KafkaHarness harness) {
      super(DockerImageName.parse(HarnessImages.KAFKA));
      this.harness = harness;
    }

    @Override
    protected void containerIsStarting(
        com.github.dockerjava.api.command.InspectContainerResponse info) {
      harness.writeStarterScript();
    }
  }

  private Producer<String, byte[]> producer;
  private Admin admin;

  private static Map<String, String> brokerEnv() {
    return Map.ofEntries(
        Map.entry("CLUSTER_ID", "relay-kraft-cluster-0"),
        Map.entry("KAFKA_NODE_ID", "1"),
        Map.entry("KAFKA_PROCESS_ROLES", "broker,controller"),
        Map.entry("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9094"),
        // Empty-host listener form (PLAINTEXT://:9092) binds all interfaces like 0.0.0.0, but the
        // literal 0.0.0.0 trips KafkaConfig.validateValues during the storage-format step even when
        // advertised.listeners is set explicitly.
        Map.entry("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9094,HOST://:" + BROKER_PORT),
        Map.entry(
            "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
            "PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT,HOST:PLAINTEXT"),
        Map.entry("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER"),
        Map.entry("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT"),
        Map.entry("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1"),
        Map.entry("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1"),
        Map.entry("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1"),
        Map.entry("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0"),
        // Placeholder; the post-start hook rewrites HOST to the real mapped host port before the
        // broker actually starts (the entrypoint blocks until the starter script lands).
        Map.entry(
            "KAFKA_ADVERTISED_LISTENERS",
            "PLAINTEXT://localhost:9092,HOST://localhost:" + BROKER_PORT));
  }

  @Override
  public void start() {
    // start() returns once the broker logged "Kafka Server started" — which only happens after the
    // containerIsStarting hook wrote the starter script with the real mapped advertised port.
    container.start();

    Properties props = new Properties();
    props.put("bootstrap.servers", bootstrapServers());
    admin = Admin.create(props);

    Properties pp = new Properties();
    pp.put("bootstrap.servers", bootstrapServers());
    pp.put("key.serializer", StringSerializer.class.getName());
    pp.put("value.serializer", ByteArraySerializer.class.getName());
    producer = new KafkaProducer<>(pp);

    createTopics(TOPIC, NAIVE_TOPIC);
  }

  public String bootstrapServers() {
    return container.getHost() + ":" + container.getMappedPort(BROKER_PORT);
  }

  private void writeStarterScript() {
    String advertised =
        "PLAINTEXT://localhost:9092,HOST://"
            + container.getHost()
            + ":"
            + container.getMappedPort(BROKER_PORT);
    String script =
        "#!/bin/sh\n"
            + "export KAFKA_ADVERTISED_LISTENERS='"
            + advertised
            + "'\n"
            + "exec /etc/kafka/docker/run\n";
    container.copyFileToContainer(Transferable.of(script.getBytes(), 0755), STARTER_SCRIPT);
  }

  private void createTopics(String... topics) {
    List<NewTopic> requests = new java.util.ArrayList<>();
    for (String t : topics) {
      requests.add(new NewTopic(t, 1, (short) 1));
    }
    try {
      admin.createTopics(requests).all().get();
    } catch (ExecutionException e) {
      // TopicAlreadyExists on a re-run is fine.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("create topics interrupted", e);
    }
  }

  @Override
  public void reset() {
    // Offsets are monotonic; idempotency keys differ per test run. Nothing topic-side to reset; the
    // fixture's await-consumed drains before truncation.
  }

  @Override
  public void stop() {
    if (producer != null) {
      producer.close();
    }
    if (admin != null) {
      admin.close();
    }
    container.stop();
  }

  /** Seeds a crafted message.posted event directly to a topic. */
  public void publish(Events.MessagePosted event, String topic) {
    try {
      producer.send(new ProducerRecord<>(topic, event.channelId(), codec.serialize(event))).get();
    } catch (ExecutionException e) {
      throw new IllegalStateException("seed publish failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("seed publish interrupted", e);
    }
  }

  /**
   * Blocks until the consumer group has committed everything published to a topic (committed offset
   * &gt;= end offset on partition 0). Because the app commits only after the projector persists,
   * this implies every consumed event's effects are durable — the deterministic "settled" assertion
   * (no bounded sleep).
   */
  public void awaitConsumed(String topic, String group) {
    TopicPartition tp = new TopicPartition(topic, 0);
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      long end = endOffset(tp);
      if (end == 0) {
        return;
      }
      if (end > 0 && committedOffset(tp, group) >= end) {
        return;
      }
      sleep(100);
    }
    throw new IllegalStateException("kafka group never settled: " + group + "@" + topic);
  }

  private long endOffset(TopicPartition tp) {
    try {
      ListOffsetsResult res = admin.listOffsets(Map.of(tp, OffsetSpec.latest()));
      return res.partitionResult(tp).get().offset();
    } catch (Exception e) {
      return -1;
    }
  }

  private long committedOffset(TopicPartition tp, String group) {
    try {
      Map<TopicPartition, OffsetAndMetadata> offsets =
          admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get();
      OffsetAndMetadata o = offsets.get(tp);
      return o == null ? -1 : o.offset();
    } catch (Exception e) {
      return -1;
    }
  }

  /**
   * Pauses the broker container — produce requests then time out exactly as if it were gone (a
   * stopped KRaft container trips over its formatted storage on restart; pausing freezes it and
   * unpausing recovers deterministically). Sanctioned by 04-dependencies.md §3.
   */
  public void stopBroker() {
    container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
  }

  /**
   * Unpauses the broker and blocks until it is ready AND the suite's consumer group is back to a
   * stable assignment — so the next test never races the rejoin/rebalance (the zero-flake gate's
   * key invariant).
   */
  public void startBroker() {
    container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
    awaitBrokerReady();
    awaitGroupStable(GROUP);
  }

  private void awaitBrokerReady() {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        admin.listTopics().names().get();
        return;
      } catch (Exception e) {
        sleep(250);
      }
    }
    throw new IllegalStateException("kafka broker never became ready after unpause");
  }

  private void awaitGroupStable(String group) {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      try {
        ConsumerGroupDescription d =
            admin.describeConsumerGroups(List.of(group)).all().get().get(group);
        if (d != null && d.state() == ConsumerGroupState.STABLE && !d.members().isEmpty()) {
          return;
        }
      } catch (Exception e) {
        // keep polling
      }
      sleep(250);
    }
    throw new IllegalStateException("kafka group never re-stabilized: " + group);
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(Duration.ofMillis(ms).toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while awaiting kafka", e);
    }
  }
}
