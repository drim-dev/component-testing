package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import dev.drim.relay.id.IdFactory;
import dev.drim.relay.naive.NaiveTxConversationWriter;
import dev.drim.relay.seams.ConversationWriter;
import dev.drim.relay.store.ConversationRepository;
import dev.drim.relay.store.DmParticipantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * G-TX naive red→green demonstration: wires {@link NaiveTxConversationWriter} (three saves, no
 * transaction) and confirms S-DM-06's catch goes RED — after the armed fault on the 2nd participant
 * insert, the conversation row and first participant survive as an orphaned partial write.
 */
@Import(GTxNaiveDemoTest.NaiveConfig.class)
class GTxNaiveDemoTest extends AcceptanceTestBase {

  @Test
  @DisplayName("G-TX naive demo: catch S-DM-06 goes red against NaiveTxConversationWriter")
  void naiveWriterLeavesOrphan() {
    String a = seedUser("gtxa");
    String b = seedUser("gtxb");

    NaiveDemoSupport.expectCatchToFail(
        "G-TX",
        () -> {
          DATABASE.armParticipantInsertFault();
          int status =
              client(a).post("/dm/conversations", RelayClient.body("recipientId", b)).status();
          assertThat(status).isEqualTo(500);
          String[] pair = {a, b};
          java.util.Arrays.sort(pair);
          assertThat(DATABASE.count("dm_conversations", "user_lo = '" + pair[0] + "'")).isZero();
          assertThat(
                  DATABASE.count(
                      "dm_participants", "user_id = '" + a + "' OR user_id = '" + b + "'"))
              .isZero();
        });
  }

  @TestConfiguration
  static class NaiveConfig {
    @Bean
    @Primary
    ConversationWriter naiveTxWriter(
        ConversationRepository conversations, DmParticipantRepository participants, IdFactory ids) {
      return new NaiveTxConversationWriter(conversations, participants, ids);
    }
  }
}
