package dev.drim.relay.seams;

import dev.drim.relay.domain.Entities;

/**
 * The G-BOLA-READ seam: the 404/403 visibility split for reading a channel's metadata/messages. The
 * naive variant ignores the private flag.
 */
public interface ChannelReadGate {
  /**
   * Returns the channel if the caller may read it, else throws an {@code ApiException} (404 for
   * private/unknown, 403 for public-non-member). {@code isMessages} distinguishes metadata (public
   * non-member allowed) from messages (public non-member → 403).
   */
  Entities.Channel authorizeRead(String channelId, String userId, boolean isMessages);
}
