package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import dev.drim.relay.naive.NaiveDmAccess;
import dev.drim.relay.seams.DmAccess;
import dev.drim.relay.store.ConversationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * G-IDOR naive red→green demonstration. Wires the {@link NaiveDmAccess} variant (load-by-id, no
 * participant check) as the {@code @Primary} DmAccess for THIS context only, then runs S-DM-08's
 * own catching assertions through {@link NaiveDemoSupport#expectCatchToFail} — which passes
 * precisely because the catch goes RED against the naive variant (the intruder gets a 200 + the
 * conversation instead of the existence-hiding 404). The correct app (DirectMessagesTest) keeps the
 * catch green; this proves the catch actually detects the bug.
 */
@Import(GIdorNaiveDemoTest.NaiveConfig.class)
class GIdorNaiveDemoTest extends AcceptanceTestBase {

  @Test
  @DisplayName("G-IDOR naive demo: catch S-DM-08 goes red against NaiveDmAccess (expect-failure)")
  void naiveDmAccessLeaksConversation() {
    String a = seedUser("gidora");
    String b = seedUser("gidorb");
    String c = seedUser("gidorc");
    String convId = seedConversation(a, b);

    NaiveDemoSupport.expectCatchToFail(
        "G-IDOR",
        () -> {
          // S-DM-08's catching assertions, verbatim: a non-participant must get the
          // existence-hiding
          // 404 (byte-identical to an unknown id). Against the naive variant the intruder gets 200.
          String hidden =
              client(c).get("/dm/conversations/" + convId).expectStatus(404).bodyString();
          String unknown =
              client(c).get("/dm/conversations/0000000000000").expectStatus(404).bodyString();
          assertThat(hidden).isEqualTo(unknown);
        });
  }

  @TestConfiguration
  static class NaiveConfig {
    @Bean
    @Primary
    DmAccess naiveDmAccess(ConversationRepository conversations) {
      return new NaiveDmAccess(conversations);
    }
  }
}
