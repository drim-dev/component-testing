package dev.drim.relay.infra;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.Map;

/**
 * Declares the quorum notification queue + its DLQ with the SAME arguments used by the publisher,
 * the worker, and the harness (a mismatched redeclare is a channel error). {@code x-delivery-limit}
 * is a broker-side backstop only — it counts dead-letter republishes, not requeued nacks, so the
 * worker enforces the attempt cap itself via {@code x-acquired-count} (see {@link
 * NotificationWorker}). Mirrors DeclareNotificationQueues in go/src/relay/infra/rabbit.go.
 */
public final class NotificationQueues {
  public static final String NOTIFY_QUEUE = "notify.dm";

  private NotificationQueues() {}

  public static String deadLetterQueue(String queue) {
    return queue + ".dlq";
  }

  public static void declare(Channel channel, String queue) throws IOException {
    String dlq = deadLetterQueue(queue);
    channel.queueDeclare(dlq, true, false, false, Map.of("x-queue-type", "quorum"));
    channel.queueDeclare(
        queue,
        true,
        false,
        false,
        Map.of(
            "x-queue-type",
            "quorum",
            "x-delivery-limit",
            2,
            "x-dead-letter-exchange",
            "",
            "x-dead-letter-routing-key",
            dlq));
  }
}
