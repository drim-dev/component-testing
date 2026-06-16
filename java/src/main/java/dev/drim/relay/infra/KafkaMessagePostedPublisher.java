package dev.drim.relay.infra;

import dev.drim.relay.domain.Events;
import dev.drim.relay.seams.MessagePostedPublisher;
import dev.drim.relay.web.ApiException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;

/**
 * The correct G-KAFKA producer seam: it AWAITS broker confirmation. If the broker is unavailable
 * the synchronous produce fails (or times out at {@link #CONFIRM_TIMEOUT}), the caller rolls back
 * the message transaction, and the API answers 503 — never fire-and-forget. The naive variant fires
 * and forgets. Mirrors go/src/relay/infra/kafka.go.
 */
@Component
public class KafkaMessagePostedPublisher implements MessagePostedPublisher {
  public static final String TOPIC = "message-posted";

  private final String topic;

  /**
   * Bounds how long a post waits for broker confirmation. A reachable broker acks in milliseconds;
   * a down broker must surface as 503 promptly and deterministically (the pinned broker-down
   * behavior + the zero-flake gate), never hang on the producer's retries.
   */
  private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(3);

  private final Producer<String, byte[]> producer;
  private final MessagePostedCodec codec;

  /**
   * The topic is configurable so a test context can produce to an isolated topic (no
   * cross-context).
   */
  public KafkaMessagePostedPublisher(
      Producer<String, byte[]> producer,
      MessagePostedCodec codec,
      @org.springframework.beans.factory.annotation.Value(
              "${relay.kafka.feed-topic:message-posted}")
          String topic) {
    this.producer = producer;
    this.codec = codec;
    this.topic = topic;
  }

  @Override
  public void publish(Events.MessagePosted event) {
    ProducerRecord<String, byte[]> record =
        new ProducerRecord<>(topic, event.channelId(), codec.serialize(event));
    try {
      Future<?> ack = producer.send(record);
      ack.get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException | ExecutionException e) {
      throw ApiException.unavailable("events:unavailable", "The event broker is unavailable.");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ApiException.unavailable("events:unavailable", "The event broker is unavailable.");
    }
  }
}
