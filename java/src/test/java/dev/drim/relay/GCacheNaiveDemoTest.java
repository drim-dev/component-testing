package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import dev.drim.relay.naive.NaiveMembershipWriter;
import dev.drim.relay.seams.MembershipWriter;
import dev.drim.relay.store.ChannelMemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * G-CACHE naive red→green demonstration: wires {@link NaiveMembershipWriter} (no cache
 * invalidation) and confirms S-CH-16's catch goes RED against it — after the kick the stale
 * members:{ch} cache still lists B.
 */
@Import(GCacheNaiveDemoTest.NaiveConfig.class)
class GCacheNaiveDemoTest extends AcceptanceTestBase {

  @Test
  @DisplayName("G-CACHE naive demo: catch S-CH-16 goes red against NaiveMembershipWriter")
  void naiveWriterLeavesStaleCache() {
    String owner = seedUser("gco");
    String b = seedUser("gcb");
    String priv = seedChannel(owner, "gcache", true);
    seedMember(owner, priv, b);

    NaiveDemoSupport.expectCatchToFail(
        "G-CACHE",
        () -> {
          REDIS.seedMembershipCache(priv, owner, b);
          client(owner).delete("/channels/" + priv + "/members/" + b).expectStatus(204);
          assertThat(REDIS.cacheMember(priv, b).member()).isFalse();
        });
  }

  @TestConfiguration
  static class NaiveConfig {
    @Bean
    @Primary
    MembershipWriter naiveMembershipWriter(ChannelMemberRepository members) {
      return new NaiveMembershipWriter(members);
    }
  }
}
