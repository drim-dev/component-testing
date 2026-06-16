package dev.drim.relay.infra;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.MessageProperties;
import dev.drim.relay.domain.Events;
import dev.drim.relay.seams.NotificationJobs;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * Publishes a DM notification job in publisher-confirm mode, AWAITING the broker's confirmation —
 * the pinned post-commit publish (a failure after commit → 500). Mirrors NotificationJobs.Enqueue
 * in go/src/relay/infra/rabbit.go.
 */
@Component
public class RabbitNotificationJobs implements NotificationJobs {
  private static final long CONFIRM_TIMEOUT_MS = 5_000;

  private final Connection connection;
  private final NotificationJobCodec codec;
  private final String queue;

  public RabbitNotificationJobs(
      Connection connection,
      NotificationJobCodec codec,
      @org.springframework.beans.factory.annotation.Value("${relay.rabbit.notify-queue:notify.dm}")
          String queue) {
    this.connection = connection;
    this.codec = codec;
    this.queue = queue;
  }

  @Override
  public void enqueue(Events.NotificationJob job) {
    try (Channel channel = connection.createChannel()) {
      channel.confirmSelect();
      NotificationQueues.declare(channel, queue);
      AMQP.BasicProperties props =
          MessageProperties.PERSISTENT_TEXT_PLAIN.builder().contentType("application/json").build();
      channel.basicPublish("", queue, props, codec.serialize(job));
      if (!channel.waitForConfirms(CONFIRM_TIMEOUT_MS)) {
        throw new IllegalStateException("broker nacked the notification job");
      }
    } catch (IOException | TimeoutException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new IllegalStateException("failed to enqueue notification job", e);
    }
  }
}
