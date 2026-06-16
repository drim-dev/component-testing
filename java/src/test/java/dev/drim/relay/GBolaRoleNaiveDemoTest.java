package dev.drim.relay;

import dev.drim.relay.naive.NaiveChannelRoleGate;
import dev.drim.relay.seams.ChannelRoleGate;
import dev.drim.relay.store.ChannelMemberRepository;
import dev.drim.relay.store.ChannelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * G-BOLA-ROLE naive red→green demonstration: wires {@link NaiveChannelRoleGate} (member check, no
 * role compare) and confirms S-CH-15's catch goes RED against it (a plain member kicks another
 * member and gets 204 instead of 403).
 */
@Import(GBolaRoleNaiveDemoTest.NaiveConfig.class)
class GBolaRoleNaiveDemoTest extends AcceptanceTestBase {

  @Test
  @DisplayName("G-BOLA-ROLE naive demo: catch S-CH-15 goes red against NaiveChannelRoleGate")
  void naiveRoleGateLetsMemberKick() {
    String owner = seedUser("gbro");
    String m1 = seedUser("gbr1");
    String m2 = seedUser("gbr2");
    String ch = seedChannel(owner, "gbrole", false);
    seedMember(owner, ch, m1);
    seedMember(owner, ch, m2);

    NaiveDemoSupport.expectCatchToFail(
        "G-BOLA-ROLE",
        () -> client(m1).delete("/channels/" + ch + "/members/" + m2).expectStatus(403));
  }

  @TestConfiguration
  static class NaiveConfig {
    @Bean
    @Primary
    ChannelRoleGate naiveChannelRoleGate(
        ChannelRepository channels, ChannelMemberRepository members) {
      return new NaiveChannelRoleGate(channels, members);
    }
  }
}
