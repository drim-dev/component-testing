package dev.drim.relay.naive;

import dev.drim.relay.domain.Events;
import dev.drim.relay.id.IdFactory;
import dev.drim.relay.seams.NotificationRecorder;
import dev.drim.relay.store.NotificationRepository;
import dev.drim.relay.store.entity.NotificationRow;
import java.time.Instant;

/**
 * The G-RABBIT naive variant (exhibit, NEVER in src/): it inserts with NO duplicate handling. A
 * redelivered duplicate (same dm_message_id) hits the UNIQUE constraint, the exception bubbles, the
 * worker nacks, and the SAME job is redelivered and crash-loops until it is dead-lettered — so a
 * duplicate (which should be a silent ack) ends up in the DLQ. Caught by NotificationsTest S-NT-02
 * + GRabbitNaiveDemoTest.
 */
public final class NaiveNotificationRecorder implements NotificationRecorder {
  private final NotificationRepository notifications;
  private final IdFactory ids;

  public NaiveNotificationRecorder(NotificationRepository notifications, IdFactory ids) {
    this.notifications = notifications;
    this.ids = ids;
  }

  @Override
  public void record(Events.NotificationJob job) {
    // The bug: no catch of the UNIQUE(dm_message_id) violation — a redelivered duplicate throws and
    // the worker treats it as a processing failure, so it crash-loops to the DLQ instead of acking.
    notifications.saveAndFlush(
        new NotificationRow(
            ids.create(),
            job.recipientId(),
            job.dmMessageId(),
            job.conversationId(),
            job.senderId(),
            job.preview(),
            Instant.now()));
  }
}
