package dev.drim.relay.infra;

import dev.drim.relay.seams.Heartbeats;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Marks a user online (TTL 60 s) by writing the SAME Redis key the presence gRPC service reads, so
 * a heartbeat is observable through both presence paths.
 */
@Component
public class RedisHeartbeats implements Heartbeats {
  private static final Duration TTL = Duration.ofSeconds(60);

  private final StringRedisTemplate redis;

  public RedisHeartbeats(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public void mark(String userId) {
    redis.opsForValue().set(PresenceKeys.KEY_PREFIX + userId, "1", TTL);
  }
}
