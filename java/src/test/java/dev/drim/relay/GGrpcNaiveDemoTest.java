package dev.drim.relay;

import dev.drim.relay.naive.NaivePresenceClient;
import dev.drim.relay.seams.PresenceClient;
import io.grpc.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * G-GRPC naive red→green demonstration: wires {@link NaivePresenceClient} (swallows the mid-stream
 * RpcException, returns the partial list as complete) and confirms S-PR-04's catch goes RED — a
 * truncated stream yields a 200 with a short member list instead of the correct 502
 * presence:incomplete.
 */
@Import(GGrpcNaiveDemoTest.NaiveConfig.class)
class GGrpcNaiveDemoTest extends AcceptanceTestBase {

  @Test
  @DisplayName("G-GRPC naive demo: catch S-PR-04 goes red against NaivePresenceClient")
  void naiveClientReportsPartialAsComplete() {
    String owner = seedUser("ggro");
    String ch = seedChannel(owner, "ggrpc", false);
    for (int i = 0; i < 4; i++) {
      seedMember(owner, ch, seedUser("ggrm" + i));
    }

    NaiveDemoSupport.expectCatchToFail(
        "G-GRPC",
        () -> {
          PRESENCE.failStreamAfter(2);
          client(owner)
              .get("/channels/" + ch + "/presence")
              .expectStatus(502)
              .expectCode("presence:incomplete");
        });
  }

  @TestConfiguration
  static class NaiveConfig {
    @Bean
    @Primary
    PresenceClient naivePresenceClient(Channel channel) {
      return new NaivePresenceClient(channel);
    }
  }
}
