package dev.drim.relay.seams;

import dev.drim.relay.domain.Events;

/**
 * The G-RABBIT seam: insert treating a duplicate (unique violation) as success so the worker acks.
 * The naive variant never handles the duplicate and crashes (poison job crash-loops instead of
 * dead-lettering).
 */
public interface NotificationRecorder {
  void record(Events.NotificationJob job);
}
