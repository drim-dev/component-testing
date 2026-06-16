package dev.drim.relay.harness;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.List;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The Redis harness (cache / counters / breaker state). Real container via {@code GenericContainer}
 * (no Testcontainers Redis module on the classpath; the digest pin is what matters). Seed = set
 * keys directly (pre-warm a membership cache to prove invalidation); Assert = read keys/TTLs; Reset
 * = FLUSHDB (the trivially fast reset — contrast with Postgres). Mirrors go/harness/redis.go.
 */
public final class RedisHarness implements DependencyHarness {
  private static final int PORT = 6379;

  private final GenericContainer<?> container =
      new GenericContainer<>(DockerImageName.parse(HarnessImages.REDIS))
          .withExposedPorts(PORT)
          .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));

  private RedisClient client;
  private StatefulRedisConnection<String, String> conn;

  @Override
  public void start() {
    container.start();
    client = RedisClient.create(RedisURI.create(host(), port()));
    conn = client.connect();
  }

  public String host() {
    return container.getHost();
  }

  public int port() {
    return container.getMappedPort(PORT);
  }

  private RedisCommands<String, String> sync() {
    return conn.sync();
  }

  @Override
  public void reset() {
    sync().flushdb();
  }

  @Override
  public void stop() {
    if (conn != null) {
      conn.close();
    }
    if (client != null) {
      client.shutdown();
    }
    container.stop();
  }

  /** Pre-warms members:{channelId} so a test can prove invalidation (G-CACHE). */
  public void seedMembershipCache(String channelId, String... memberIds) {
    String key = "members:" + channelId;
    RedisCommands<String, String> r = sync();
    r.del(key);
    if (memberIds.length > 0) {
      r.sadd(key, memberIds);
      r.expire(key, 300);
    }
  }

  /** Reports whether the cached set for a channel exists and contains a user. */
  public CacheState cacheMember(String channelId, String userId) {
    String key = "members:" + channelId;
    RedisCommands<String, String> r = sync();
    if (r.exists(key) == 0) {
      return new CacheState(false, false);
    }
    return new CacheState(true, r.sismember(key, userId));
  }

  public List<String> keys(String pattern) {
    return sync().keys(pattern);
  }

  /** The membership-cache observation: whether the channel's set exists, and contains the user. */
  public record CacheState(boolean exists, boolean member) {}
}
