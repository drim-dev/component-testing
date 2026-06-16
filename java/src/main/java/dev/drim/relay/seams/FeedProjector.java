package dev.drim.relay.seams;

import dev.drim.relay.domain.Events;

/**
 * The G-KAFKA consumer seam: idempotent feed insert + increment-on-first-insert. The naive variant
 * inserts and increments unconditionally.
 */
public interface FeedProjector {
  void apply(Events.MessagePosted event);
}
