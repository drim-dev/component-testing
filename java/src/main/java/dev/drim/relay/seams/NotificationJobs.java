package dev.drim.relay.seams;

import dev.drim.relay.domain.Events;

/**
 * Publishes a DM notification job (RabbitMQ) after the message commits, awaiting the broker's
 * publisher confirmation.
 */
public interface NotificationJobs {
  void enqueue(Events.NotificationJob job);
}
