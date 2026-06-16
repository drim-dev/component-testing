package dev.drim.relay.infra;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import dev.drim.relay.domain.Events;
import dev.drim.relay.seams.NotificationRecorder;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Consumes notify.dm with manual acks, prefetch 1: a job is acked only AFTER the recorder persists
 * its effect. A failing job is retried up to {@link #MAX_ATTEMPTS}, then dead-lettered (a final
 * requeue:false nack routes it to the DLX → DLQ deterministically), so the queue keeps flowing past
 * a poison job.
 *
 * <p>The attempt cap is enforced here via {@code x-acquired-count} (the header a quorum queue
 * stamps on a requeued nack), NOT by leaning on the broker's {@code x-delivery-limit} (which counts
 * dead-letter republishes, not requeued nacks, so it would loop). The correct recorder treats a
 * redelivered duplicate as success → ack, so a duplicate never crash-loops; the naive variant does
 * not, so a redelivered duplicate dead-letters after MAX_ATTEMPTS. Mirrors
 * go/src/relay/workers/notificationworker.go.
 *
 * <p>Not a {@code @Component}: the composition root wires it with the SAME {@link
 * NotificationRecorder} seam the app uses, so a consumer-side naive variant injects through the
 * same constructor seam.
 */
public class NotificationWorker {
  public static final int MAX_ATTEMPTS = 3;

  private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);

  private final Connection connection;
  private final NotificationRecorder recorder;
  private final NotificationJobCodec codec;
  private final String queue;

  private Channel channel;

  public NotificationWorker(
      Connection connection, NotificationRecorder recorder, NotificationJobCodec codec) {
    this(connection, recorder, codec, NotificationQueues.NOTIFY_QUEUE);
  }

  /**
   * The queue is configurable so a test context can consume an isolated queue (no cross-context).
   */
  public NotificationWorker(
      Connection connection,
      NotificationRecorder recorder,
      NotificationJobCodec codec,
      String queue) {
    this.connection = connection;
    this.recorder = recorder;
    this.codec = codec;
    this.queue = queue;
  }

  public void start() {
    try {
      channel = connection.createChannel();
      NotificationQueues.declare(channel, queue);
      channel.basicQos(1);
      channel.basicConsume(queue, false, new JobConsumer(channel));
    } catch (IOException e) {
      throw new IllegalStateException("failed to start notification worker", e);
    }
  }

  public void stop() {
    if (channel != null && channel.isOpen()) {
      try {
        channel.close();
      } catch (Exception e) {
        log.debug("error closing notification worker channel", e);
      }
    }
  }

  private final class JobConsumer extends DefaultConsumer {
    JobConsumer(Channel channel) {
      super(channel);
    }

    @Override
    public void handleDelivery(
        String consumerTag, Envelope envelope, AMQP.BasicProperties properties, byte[] body)
        throws IOException {
      boolean ok;
      try {
        Events.NotificationJob job = codec.deserialize(body);
        recorder.record(job);
        ok = true;
      } catch (RuntimeException e) {
        log.debug("notification job failed", e);
        ok = false;
      }
      if (ok) {
        getChannel().basicAck(envelope.getDeliveryTag(), false);
        return;
      }
      boolean exhausted = acquiredCount(properties) >= MAX_ATTEMPTS;
      getChannel().basicNack(envelope.getDeliveryTag(), false, !exhausted);
    }
  }

  /**
   * Returns this delivery attempt (1-based). A quorum queue stamps {@code x-acquired-count} = prior
   * acquisitions on a requeued nack (absent on first delivery).
   */
  static long acquiredCount(AMQP.BasicProperties properties) {
    if (properties == null || properties.getHeaders() == null) {
      return 1;
    }
    Object raw = properties.getHeaders().get("x-acquired-count");
    if (raw instanceof Number n) {
      return n.longValue() + 1;
    }
    return 1;
  }
}
