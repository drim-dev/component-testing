package dev.drim.relay.naive;

import dev.drim.relay.domain.Events;
import dev.drim.relay.infra.KafkaMessagePostedPublisher;
import dev.drim.relay.infra.MessagePostedCodec;
import dev.drim.relay.seams.MessagePostedPublisher;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * The G-KAFKA producer naive variant (exhibit, NEVER in src/): fire-and-forget. It calls {@code
 * send()} and never awaits the broker's confirmation, so a DOWN broker silently swallows the event
 * and the post still returns 201 — instead of the correct 503 events:unavailable with the row
 * rolled back. Caught by FeedTest S-FD-01 + GKafkaProducerNaiveDemoTest.
 */
public final class NaiveMessagePostedPublisher implements MessagePostedPublisher {
  private final Producer<String, byte[]> producer;
  private final MessagePostedCodec codec;

  public NaiveMessagePostedPublisher(Producer<String, byte[]> producer, MessagePostedCodec codec) {
    this.producer = producer;
    this.codec = codec;
  }

  @Override
  public void publish(Events.MessagePosted event) {
    // The bug: no .get() on the send future — the broker's ack (or failure) is never awaited.
    producer.send(
        new ProducerRecord<>(
            KafkaMessagePostedPublisher.TOPIC, event.channelId(), codec.serialize(event)));
  }
}
