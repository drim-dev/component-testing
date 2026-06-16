package dev.drim.relay;

import dev.drim.relay.harness.DatabaseHarness;
import dev.drim.relay.harness.KafkaHarness;
import dev.drim.relay.harness.LlmHarness;
import dev.drim.relay.harness.PresenceHarness;
import dev.drim.relay.harness.RabbitMqHarness;
import dev.drim.relay.harness.RedisHarness;
import dev.drim.relay.harness.S3Harness;
import dev.drim.relay.harness.UnfurlHarness;
import dev.drim.relay.seams.SummaryModel;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The shared component-test fixture: boots the FULL Relay app (real HTTP boundary, RANDOM_PORT)
 * wired against all eight real/realistic dependencies, exactly like the production composition root
 * but with Testcontainers coordinates. One JVM-lifetime fixture (one Docker host, one suite); every
 * test resets all dependencies first. The analog of Go's harness.Fixture / NewApp(Deps) and the
 * .NET WebApplicationFactory base.
 *
 * <p>Harness ownership: the eight harnesses are started once in a static initializer (so the
 * containers survive across every test class that extends this base — Spring caches the context by
 * the same property set), their connection coordinates are published to the Spring environment via
 * {@link DynamicPropertySource}, and the LLM port is overridden with the interaction-verifying fake
 * as a {@code @Primary} bean. The naive-seam gallery triples add their own nested
 * {@code @TestConfiguration} that overrides exactly one MORE bean on top of this base.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.context.annotation.Import(AcceptanceTestBase.HarnessConfig.class)
public abstract class AcceptanceTestBase {

  protected static final DatabaseHarness DATABASE = new DatabaseHarness();
  protected static final RedisHarness REDIS = new RedisHarness();
  protected static final KafkaHarness KAFKA = new KafkaHarness();
  protected static final RabbitMqHarness RABBIT = new RabbitMqHarness();
  protected static final S3Harness S3 = new S3Harness();
  protected static final UnfurlHarness UNFURL = new UnfurlHarness();
  protected static final LlmHarness LLM = new LlmHarness();
  protected static PresenceHarness PRESENCE;

  private static boolean txFaultInstalled = false;

  static {
    DATABASE.start();
    REDIS.start();
    KAFKA.start();
    RABBIT.start();
    S3.start();
    UNFURL.start();
    LLM.start();
    // Presence shares the suite Redis, so it can only be built after Redis is up (honest
    // inter-harness dependency, mirroring the Go fixture order).
    PRESENCE = new PresenceHarness(REDIS.host(), REDIS.port());
    PRESENCE.start();

    // Only the in-process harnesses are torn down here. The container-backed harnesses (Postgres,
    // Redis, Kafka, RabbitMQ, MinIO) are NOT stopped in this hook: Spring's own JVM shutdown hook
    // closes its connection beans (producer/consumer/Rabbit connection/S3 client) against the still
    // live containers, and the two shutdown hooks have no ordering guarantee — stopping the
    // containers here first leaves Spring closing dead sockets (EOF/AlreadyClosed WARNs). The
    // Testcontainers Ryuk reaper removes the containers at JVM exit, so nothing leaks.
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  PRESENCE.stop();
                  LLM.stop();
                  UNFURL.stop();
                }));
  }

  @DynamicPropertySource
  static void wireDependencies(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", DATABASE::jdbcUrl);
    registry.add("spring.datasource.username", DATABASE::username);
    registry.add("spring.datasource.password", DATABASE::password);

    registry.add("spring.data.redis.host", REDIS::host);
    registry.add("spring.data.redis.port", REDIS::port);

    registry.add("relay.kafka.bootstrap-servers", KAFKA::bootstrapServers);
    registry.add("relay.rabbit.uri", RABBIT::amqpUrl);
    registry.add("relay.presence.target", PRESENCE::target);
    registry.add("relay.unfurl.base-url", UNFURL::baseUrl);

    registry.add("relay.s3.endpoint", S3::endpoint);
    registry.add("relay.s3.access-key", S3::accessKey);
    registry.add("relay.s3.secret-key", S3::secretKey);

    // Each gallery naive-demo test wires a distinct @Primary seam, so Spring builds (and caches) a
    // separate ApplicationContext per config — each with its own Hikari pool against the one shared
    // Postgres container. A default 10-connection pool × ~13 cached contexts blows past Postgres's
    // max_connections ("too many clients"). Cap the pool small; the suite is serial
    // (maxParallelForks
    // = 1), so a handful of connections per context is plenty.
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
  }

  @LocalServerPort protected int port;

  @BeforeEach
  void resetHarnesses() {
    if (!txFaultInstalled) {
      DATABASE.installTxFault();
      txFaultInstalled = true;
    }
    DATABASE.reset();
    REDIS.reset();
    KAFKA.reset();
    RABBIT.reset();
    S3.reset();
    UNFURL.reset();
    LLM.reset();
    PRESENCE.reset();
  }

  protected String baseUrl() {
    return "http://127.0.0.1:" + port;
  }

  protected RelayClient client(String userId) {
    return new RelayClient(baseUrl(), userId);
  }

  /** Anonymous client (no X-User-Id) — the identity scenarios. */
  protected RelayClient anonymous() {
    return new RelayClient(baseUrl(), null);
  }

  // ---- seed helpers: write THROUGH the real constraints so seeded states are reachable ----

  protected String seedUser(String handle) {
    return client(null)
        .post("/users", RelayClient.body("handle", handle, "displayName", handle))
        .expectStatus(201)
        .string("id");
  }

  protected String seedChannel(String ownerId, String name, boolean isPrivate) {
    return client(ownerId)
        .post("/channels", RelayClient.body("name", name, "private", isPrivate))
        .expectStatus(201)
        .string("id");
  }

  protected void seedMember(String byUserId, String channelId, String targetUserId) {
    client(byUserId)
        .post("/channels/" + channelId + "/members", RelayClient.body("userId", targetUserId))
        .expectStatus(201);
  }

  protected String seedConversation(String aId, String bId) {
    RelayClient.Response resp =
        client(aId).post("/dm/conversations", RelayClient.body("recipientId", bId));
    if (resp.status() != 200 && resp.status() != 201) {
      throw new IllegalStateException("seed conversation failed: " + resp.bodyString());
    }
    return resp.string("id");
  }

  protected void seedDmMessage(String senderId, String conversationId, String text) {
    client(senderId)
        .post("/dm/conversations/" + conversationId + "/messages", RelayClient.body("text", text))
        .expectStatus(201);
  }

  /**
   * Attaches the interaction-verifying LLM fake as the {@link SummaryModel} the app uses,
   * overriding the production {@code NotConfiguredSummaryModel}. The fake captures every request so
   * the G-LLM scenarios can verify the prompt-injection containment.
   */
  @TestConfiguration
  static class HarnessConfig {
    @Bean
    @Primary
    SummaryModel summaryModelFake() {
      return LLM.model();
    }
  }
}
