package dev.drim.relay.harness;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Confirms the harness foundation actually boots against real Docker (digest-pinned images): the
 * Postgres and Redis harnesses start, the Redis seed/assert/reset round-trips, and both tear down
 * cleanly. This is the early "does the harness even boot" check before the full acceptance suite —
 * NOT a gallery scenario. The broker harnesses (Kafka KRaft workaround, RabbitMQ quorum) are
 * exercised by the determinism batches once the full suite lands.
 */
class HarnessSmokeTest {

  @Test
  void postgresAndRedisHarnessesBootAndReset() {
    DatabaseHarness db = new DatabaseHarness();
    RedisHarness redis = new RedisHarness();
    try {
      db.start();
      redis.start();

      assertThat(db.jdbcUrl()).startsWith("jdbc:postgresql://");
      assertThat(redis.port()).isPositive();

      redis.seedMembershipCache("chan-1", "user-a", "user-b");
      RedisHarness.CacheState before = redis.cacheMember("chan-1", "user-a");
      assertThat(before.exists()).isTrue();
      assertThat(before.member()).isTrue();

      redis.reset();
      RedisHarness.CacheState after = redis.cacheMember("chan-1", "user-a");
      assertThat(after.exists()).isFalse();
    } finally {
      redis.stop();
      db.stop();
    }
  }
}
