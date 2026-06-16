package dev.drim.relay.seams;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.domain.Role;

/**
 * The G-BOLA-ROLE seam: membership AND role check for admin actions. The naive variant checks
 * membership but skips the role compare.
 */
public interface ChannelRoleGate {
  /**
   * Returns the caller's membership if they are a member with at least {@code minRole}, else throws
   * an {@code ApiException} (404 private/unknown, 403 visible-but-forbidden). The returned
   * membership lets the handler apply finer rules (e.g. kicking an admin).
   */
  Entities.ChannelMember authorizeRole(String channelId, String userId, Role minRole);
}
