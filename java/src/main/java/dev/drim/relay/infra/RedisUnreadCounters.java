package dev.drim.relay.infra;

import dev.drim.relay.seams.UnreadCounters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** The Redis per-channel unread counter (unread:{userId}:{channelId}). */
@Component
public class RedisUnreadCounters implements UnreadCounters {
  private final StringRedisTemplate redis;

  public RedisUnreadCounters(StringRedisTemplate redis) {
    this.redis = redis;
  }

  private static String key(String userId, String channelId) {
    return "unread:" + userId + ":" + channelId;
  }

  @Override
  public void increment(String userId, String channelId) {
    redis.opsForValue().increment(key(userId, channelId));
  }

  @Override
  public void reset(String userId, String channelId) {
    redis.delete(key(userId, channelId));
  }

  @Override
  public Map<String, Long> forUser(String userId) {
    String prefix = "unread:" + userId + ":";
    Map<String, Long> out = new HashMap<>();
    ScanOptions options = ScanOptions.scanOptions().match(prefix + "*").count(100).build();
    try (Cursor<String> cursor = redis.scan(options)) {
      List<String> keys = cursor.stream().toList();
      for (String key : keys) {
        String channelId = key.substring(prefix.length());
        String raw = redis.opsForValue().get(key);
        out.put(channelId, raw == null ? 0L : Long.parseLong(raw));
      }
    }
    return out;
  }
}
