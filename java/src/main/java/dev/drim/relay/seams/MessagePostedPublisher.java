package dev.drim.relay.seams;

import dev.drim.relay.domain.Events;

/**
 * The G-KAFKA producer seam: publish awaiting broker confirmation (broker down → exception → 503,
 * message not persisted). The naive variant fires and forgets.
 */
public interface MessagePostedPublisher {
  void publish(Events.MessagePosted event);
}
