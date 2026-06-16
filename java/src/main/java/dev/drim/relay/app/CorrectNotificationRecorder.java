package dev.drim.relay.app;

import dev.drim.relay.domain.Events;
import dev.drim.relay.id.IdFactory;
import dev.drim.relay.seams.NotificationRecorder;
import dev.drim.relay.store.NotificationRepository;
import dev.drim.relay.store.entity.NotificationRow;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * The correct G-RABBIT seam: insert, treating the UNIQUE(dm_message_id) violation (a redelivered
 * duplicate) as SUCCESS so the worker acks. A genuine failure (poison job — unresolvable recipient
 * FK) bubbles up to be retried then dead-lettered. The naive variant never handles the duplicate
 * and crash-loops into the DLQ instead of dead-lettering only the poison job.
 */
@Component
public class CorrectNotificationRecorder implements NotificationRecorder {
  private final NotificationRepository notifications;
  private final IdFactory ids;

  public CorrectNotificationRecorder(NotificationRepository notifications, IdFactory ids) {
    this.notifications = notifications;
    this.ids = ids;
  }

  @Override
  public void record(Events.NotificationJob job) {
    NotificationRow row =
        new NotificationRow(
            ids.create(),
            job.recipientId(),
            job.dmMessageId(),
            job.conversationId(),
            job.senderId(),
            job.preview(),
            Instant.now());
    try {
      notifications.saveAndFlush(row);
    } catch (DataIntegrityViolationException e) {
      if (isDuplicate(e)) {
        return; // redelivered duplicate — already recorded. Success → ack.
      }
      throw e; // poison job (e.g. unresolvable recipient FK) → retry then DLQ
    }
  }

  /**
   * Distinguishes the idempotency duplicate (UNIQUE dm_message_id) from a genuine failure such as a
   * recipient FK violation. Only the former is an ack; the latter must bubble so the broker
   * dead-letters the poison job rather than the worker crash-looping on it.
   */
  private static boolean isDuplicate(DataIntegrityViolationException e) {
    String message = e.getMostSpecificCause().getMessage();
    return message != null && message.contains("dm_message_id");
  }
}
