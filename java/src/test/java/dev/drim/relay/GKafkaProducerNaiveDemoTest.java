package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;

import dev.drim.relay.infra.MessagePostedCodec;
import dev.drim.relay.naive.NaiveMessagePostedPublisher;
import dev.drim.relay.seams.MessagePostedPublisher;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * G-KAFKA producer naive red→green demonstration: wires {@link NaiveMessagePostedPublisher}
 * (fire-and-forget) and confirms S-FD-01's catch goes RED — with the broker paused the post still
 * returns 201 (and writes the row) instead of the correct 503 with the row rolled back.
 */
@Import(GKafkaProducerNaiveDemoTest.NaiveConfig.class)
class GKafkaProducerNaiveDemoTest extends AcceptanceTestBase {

  @Test
  @DisplayName(
      "G-KAFKA producer naive demo: catch S-FD-01 goes red against NaiveMessagePostedPublisher")
  void naivePublisherSwallowsBrokerDown() {
    String owner = seedUser("gkp");
    String ch = seedChannel(owner, "gkprod", false);

    NaiveDemoSupport.expectCatchToFail(
        "G-KAFKA-producer",
        () -> {
          KAFKA.stopBroker();
          try {
            client(owner)
                .post("/channels/" + ch + "/messages", RelayClient.body("text", "lost"))
                .expectStatus(503)
                .expectCode("events:unavailable");
            assertThat(DATABASE.count("channel_messages", "channel_id = '" + ch + "'")).isZero();
          } finally {
            KAFKA.startBroker();
          }
        });
  }

  @TestConfiguration
  static class NaiveConfig {
    @Bean
    @Primary
    MessagePostedPublisher naivePublisher(
        Producer<String, byte[]> producer, MessagePostedCodec codec) {
      return new NaiveMessagePostedPublisher(producer, codec);
    }
  }
}
