package dev.drim.relay.infra;

import dev.drim.relay.domain.Events;
import dev.drim.relay.seams.FeedProjector;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The feed-fanout consumer group. Delivery is at-least-once and the projector is idempotent
 * (G-KAFKA consumer); the offset is committed only AFTER {@link FeedProjector#apply} succeeds, so a
 * processing failure is retried (never silently skipped) — and the harness's await-idle assertion
 * (group lag == 0) implies the effects are durable. Mirrors go/src/relay/workers/feedconsumer.go.
 *
 * <p>Not a {@code @Component}: the worker is wired explicitly by the composition root with the SAME
 * {@link FeedProjector} seam the app uses, so a consumer-side naive variant injects through the
 * same constructor seam.
 */
public class FeedConsumer implements Runnable {
  private static final Logger log = LoggerFactory.getLogger(FeedConsumer.class);
  private static final Duration POLL = Duration.ofMillis(250);

  private final KafkaConsumer<String, byte[]> consumer;
  private final FeedProjector projector;
  private final MessagePostedCodec codec;
  private final String topic;
  private final AtomicBoolean running = new AtomicBoolean(false);
  private Thread thread;

  public FeedConsumer(
      KafkaConsumer<String, byte[]> consumer, FeedProjector projector, MessagePostedCodec codec) {
    this(consumer, projector, codec, KafkaMessagePostedPublisher.TOPIC);
  }

  /**
   * The topic is configurable so a test context can consume an isolated topic (no cross-context).
   */
  public FeedConsumer(
      KafkaConsumer<String, byte[]> consumer,
      FeedProjector projector,
      MessagePostedCodec codec,
      String topic) {
    this.consumer = consumer;
    this.projector = projector;
    this.codec = codec;
    this.topic = topic;
  }

  public void start() {
    if (running.compareAndSet(false, true)) {
      consumer.subscribe(java.util.List.of(topic));
      thread = new Thread(this, "feed-consumer");
      thread.setDaemon(true);
      thread.start();
    }
  }

  // Joins the worker thread so the consumer's own close() (in run()) completes before the bean
  // factory disposes — otherwise Spring would proceed to tear down on a thread that races the
  // worker.
  public void stop() {
    running.set(false);
    consumer.wakeup();
    if (thread != null) {
      try {
        thread.join(Duration.ofSeconds(10).toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  @Override
  public void run() {
    while (running.get()) {
      try {
        ConsumerRecords<String, byte[]> records = consumer.poll(POLL);
        boolean failed = false;
        for (ConsumerRecord<String, byte[]> record : records) {
          if (failed) {
            break;
          }
          Events.MessagePosted event;
          try {
            event = codec.deserialize(record.value());
          } catch (RuntimeException e) {
            log.debug("skip undecodable event", e);
            continue;
          }
          try {
            projector.apply(event);
          } catch (RuntimeException e) {
            // Processing failed before commit: do not advance the offset; the records are
            // re-polled and retried. The projector's idempotency makes the retry safe.
            log.debug("feed fanout failed; will retry", e);
            failed = true;
          }
        }
        if (!failed && !records.isEmpty()) {
          consumer.commitSync();
        }
      } catch (org.apache.kafka.common.errors.WakeupException e) {
        if (!running.get()) {
          break;
        }
      } catch (RuntimeException e) {
        // Broker unavailable or transient fetch error — keep polling; the client self-heals.
        log.debug("poll error; will retry", e);
      }
    }
    consumer.close();
  }
}
