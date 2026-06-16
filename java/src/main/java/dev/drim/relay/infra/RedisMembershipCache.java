package dev.drim.relay.infra;

import dev.drim.relay.seams.MembershipCache;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The Redis membership fast-path (members:{channelId}, a set) coupled to invalidation on membership
 * writes. Its coherence with Postgres is the G-CACHE property: the correct membership writer
 * invalidates here on every write so a removed member's next read is denied immediately.
 */
@Component
public class RedisMembershipCache implements MembershipCache {
  private static final Duration TTL = Duration.ofSeconds(300);

  private final StringRedisTemplate redis;

  public RedisMembershipCache(StringRedisTemplate redis) {
    this.redis = redis;
  }

  private static String key(String channelId) {
    return "members:" + channelId;
  }

  @Override
  public Optional<Boolean> isMember(String channelId, String userId) {
    String key = key(channelId);
    if (Boolean.FALSE.equals(redis.hasKey(key))) {
      return Optional.empty();
    }
    return Optional.of(Boolean.TRUE.equals(redis.opsForSet().isMember(key, userId)));
  }

  @Override
  public void remember(String channelId, List<String> memberIds) {
    String key = key(channelId);
    redis.delete(key);
    if (!memberIds.isEmpty()) {
      redis.opsForSet().add(key, memberIds.toArray(String[]::new));
      redis.expire(key, TTL);
    }
  }

  @Override
  public void invalidate(String channelId) {
    redis.delete(key(channelId));
  }
}
