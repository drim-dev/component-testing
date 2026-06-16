package dev.drim.relay.seams;

import java.util.Map;

/** The Redis per-channel unread counter. */
public interface UnreadCounters {
  void increment(String userId, String channelId);

  void reset(String userId, String channelId);

  Map<String, Long> forUser(String userId);
}
