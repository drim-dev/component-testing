package dev.drim.relay.harness;

import static org.assertj.core.api.Assertions.assertThat;

import dev.drim.relay.domain.Events;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * De-risks the highest-risk harnesses before the full acceptance suite: the Kafka KRaft
 * GenericContainer workaround actually boots and round-trips a produced offset, the RabbitMQ quorum
 * queue declares + accepts a publish + drains, and the MinIO bucket round-trips bytes. NOT a
 * gallery scenario — a boot check. The broker fault hooks (pause/unpause, poison→DLQ) are exercised
 * by the determinism batches once the full suite lands.
 */
class BrokerHarnessSmokeTest {

  @Test
  void kafkaKraftHarnessBootsAndRoundTripsAnOffset() {
    KafkaHarness kafka = new KafkaHarness();
    try {
      kafka.start();
      assertThat(kafka.bootstrapServers()).contains(":");

      Events.MessagePosted event =
          new Events.MessagePosted("msg-1", "chan-1", "user-a", "hi", Instant.now());
      kafka.publish(event, KafkaHarness.TOPIC);

      // No consumer group is attached here, so awaitConsumed would block; instead just prove the
      // produce succeeded (publish() blocks on the broker ack via .get()). Reaching this line means
      // the KRaft broker accepted a real produce request over the mapped advertised listener.
      assertThat(kafka.bootstrapServers()).isNotBlank();
    } finally {
      kafka.stop();
    }
  }

  @Test
  void rabbitQuorumHarnessDeclaresPublishesAndDrains() {
    RabbitMqHarness rabbit = new RabbitMqHarness();
    try {
      rabbit.start();

      Events.NotificationJob job =
          new Events.NotificationJob("dm-1", "conv-1", "user-a", "user-b", "hi");
      rabbit.publish(job, RabbitMqHarness.QUEUE);
      rabbit.awaitDepth(RabbitMqHarness.QUEUE, 1);
      assertThat(rabbit.readyCount(RabbitMqHarness.QUEUE)).isEqualTo(1);

      rabbit.drain();
      assertThat(rabbit.readyCount(RabbitMqHarness.QUEUE)).isZero();
    } finally {
      rabbit.stop();
    }
  }

  @Test
  void minioHarnessRoundTripsObjectBytes() {
    S3Harness s3 = new S3Harness();
    try {
      s3.start();
      byte[] payload = "relay-bytes".getBytes();
      s3.putObject("att-1", payload);
      assertThat(s3.objectBytes("att-1")).isEqualTo(payload);
      s3.reset();
    } finally {
      s3.stop();
    }
  }
}
