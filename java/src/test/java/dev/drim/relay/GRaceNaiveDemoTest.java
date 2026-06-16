package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import dev.drim.relay.id.IdFactory;
import dev.drim.relay.naive.NaiveRaceConversationWriter;
import dev.drim.relay.seams.ConversationWriter;
import dev.drim.relay.store.ConversationRepository;
import dev.drim.relay.store.DmParticipantRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * G-RACE naive red→green demonstration: wires {@link NaiveRaceConversationWriter}
 * (check-then-insert with a widened TOCTOU window) and confirms S-DM-05's catch goes RED —
 * concurrent creates yield more than one conversation row (or a 5xx).
 */
@Import(GRaceNaiveDemoTest.NaiveConfig.class)
class GRaceNaiveDemoTest extends AcceptanceTestBase {

  @Test
  @DisplayName("G-RACE naive demo: catch S-DM-05 goes red against NaiveRaceConversationWriter")
  void naiveWriterDuplicatesUnderRace() throws Exception {
    String a = seedUser("grca");
    String b = seedUser("grcb");

    NaiveDemoSupport.expectCatchToFail(
        "G-RACE",
        () -> {
          int n = 8;
          ExecutorService pool = Executors.newFixedThreadPool(n);
          try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < n; i++) {
              tasks.add(
                  () ->
                      client(a)
                          .post("/dm/conversations", RelayClient.body("recipientId", b))
                          .status());
            }
            for (var f : pool.invokeAll(tasks)) {
              assertThat(f.get()).isIn(200, 201);
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          } finally {
            pool.shutdownNow();
          }
          String[] pair = {a, b};
          java.util.Arrays.sort(pair);
          assertThat(DATABASE.count("dm_conversations", "user_lo = '" + pair[0] + "'"))
              .isEqualTo(1);
        });
  }

  @TestConfiguration
  static class NaiveConfig {
    @Bean
    @Primary
    ConversationWriter naiveRaceWriter(
        ConversationRepository conversations, DmParticipantRepository participants, IdFactory ids) {
      return new NaiveRaceConversationWriter(conversations, participants, ids);
    }
  }
}
