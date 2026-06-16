package dev.drim.relay.seams;

import java.util.List;
import java.util.Optional;

/** The Redis authorization fast-path + its invalidation hook. */
public interface MembershipCache {
  /** Empty when the channel is not cached; otherwise the cached membership answer. */
  Optional<Boolean> isMember(String channelId, String userId);

  void remember(String channelId, List<String> memberIds);

  void invalidate(String channelId);
}
