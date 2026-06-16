package dev.drim.relay.seams;

import dev.drim.relay.domain.Entities;

/**
 * The G-CACHE seam: a membership write coupled to cache invalidation. The naive variant writes
 * Postgres and forgets to invalidate the Redis membership cache.
 */
public interface MembershipWriter {
  void add(Entities.ChannelMember member);

  void remove(String channelId, String userId);
}
