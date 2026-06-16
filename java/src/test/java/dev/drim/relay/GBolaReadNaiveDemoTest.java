package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import dev.drim.relay.naive.NaiveChannelReadGate;
import dev.drim.relay.seams.ChannelReadGate;
import dev.drim.relay.store.ChannelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * G-BOLA-READ naive red→green demonstration: wires {@link NaiveChannelReadGate} (ignores {@code
 * private}) and confirms S-CH-05's catch goes RED against it (the non-member gets 200 metadata
 * instead of the existence-hiding 404).
 */
@Import(GBolaReadNaiveDemoTest.NaiveConfig.class)
class GBolaReadNaiveDemoTest extends AcceptanceTestBase {

  @Test
  @DisplayName("G-BOLA-READ naive demo: catch S-CH-05 goes red against NaiveChannelReadGate")
  void naiveReadGateLeaksPrivateChannel() {
    String owner = seedUser("gbra");
    String nonMember = seedUser("gbrb");
    String priv = seedChannel(owner, "gbr-priv", true);

    NaiveDemoSupport.expectCatchToFail(
        "G-BOLA-READ",
        () -> {
          String hidden = client(nonMember).get("/channels/" + priv).expectStatus(404).bodyString();
          String unknown =
              client(nonMember).get("/channels/0000000000000").expectStatus(404).bodyString();
          assertThat(hidden).isEqualTo(unknown);
        });
  }

  @TestConfiguration
  static class NaiveConfig {
    @Bean
    @Primary
    ChannelReadGate naiveChannelReadGate(ChannelRepository channels) {
      return new NaiveChannelReadGate(channels);
    }
  }
}
