package dev.drim.relay.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.MessageProperties;
import dev.drim.relay.domain.Events;
import dev.drim.relay.infra.EventCodecs;
import dev.drim.relay.infra.NotificationJobCodec;
import dev.drim.relay.infra.NotificationQueues;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The RabbitMQ harness (queues / acks / DLQ — different semantics from Kafka's log/offsets). Seed =
 * publish a job directly (incl. duplicate and poison); Assert = await-until on queue stats (ready
 * via AMQP passive declare + unacked via the management API, which refreshes on an interval — so
 * "settled" must hold across two spaced samples); Reset = purge + drain. Mirrors
 * go/harness/rabbitmq.go.
 */
public final class RabbitMqHarness implements DependencyHarness {
  public static final String QUEUE = NotificationQueues.NOTIFY_QUEUE;
  public static final String NAIVE_QUEUE = "notify.dm.naive";

  private final NotificationJobCodec codec =
      new NotificationJobCodec(EventCodecs.canonicalMapper());
  private final ObjectMapper mapper = new ObjectMapper();
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  private final RabbitMQContainer container =
      new RabbitMQContainer(
              DockerImageName.parse(HarnessImages.RABBITMQ).asCompatibleSubstituteFor("rabbitmq"))
          .withEnv(
              "RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "-rabbit collect_statistics_interval 500");

  private Connection connection;
  private Channel channel;
  private String mgmtUrl;
  private String mgmtUser;
  private String mgmtPass;

  @Override
  public void start() {
    container.start();
    mgmtUser = container.getAdminUsername();
    mgmtPass = container.getAdminPassword();
    try {
      ConnectionFactory factory = new ConnectionFactory();
      factory.setUri(container.getAmqpUrl());
      connection = factory.newConnection();
      channel = connection.createChannel();
      NotificationQueues.declare(channel, QUEUE);
      NotificationQueues.declare(channel, NAIVE_QUEUE);
    } catch (Exception e) {
      throw new IllegalStateException("rabbit start failed", e);
    }
    mgmtUrl = "http://" + container.getHost() + ":" + container.getMappedPort(15672);
    awaitManagementReady();
  }

  public Connection connection() {
    return connection;
  }

  public String amqpUrl() {
    return container.getAmqpUrl();
  }

  @Override
  public void reset() {
    drain();
  }

  @Override
  public void stop() {
    try {
      if (channel != null && channel.isOpen()) {
        channel.close();
      }
      if (connection != null && connection.isOpen()) {
        connection.close();
      }
    } catch (Exception e) {
      // best-effort close at suite end
    }
    container.stop();
  }

  /** Seeds a job directly (a duplicate of a delivered one, or poison). */
  public void publish(Events.NotificationJob job, String queue) {
    try {
      AMQP.BasicProperties props =
          MessageProperties.PERSISTENT_TEXT_PLAIN.builder().contentType("application/json").build();
      channel.basicPublish("", queue, props, codec.serialize(job));
    } catch (Exception e) {
      throw new IllegalStateException("rabbit seed publish failed", e);
    }
  }

  /** The real-time ready count via AMQP passive declare (management stats lag behind). */
  public int readyCount(String queue) {
    try (Channel ch = connection.createChannel()) {
      AMQP.Queue.DeclareOk ok = ch.queueDeclarePassive(queue);
      return ok.getMessageCount();
    } catch (Exception e) {
      throw new IllegalStateException("ready count failed for " + queue, e);
    }
  }

  /**
   * Blocks until a queue is fully settled: nothing ready AND nothing in flight. Unacked is only
   * visible via the management API (interval-refreshed), so the condition must hold across TWO
   * samples spaced wider than the 500 ms stats interval.
   */
  public void awaitSettled(String queue) {
    int settledSamples = 0;
    long deadline = System.currentTimeMillis() + 30_000;
    while (settledSamples < 2 && System.currentTimeMillis() < deadline) {
      int ready = readyCount(queue);
      int unacked = unackedCount(queue);
      if (ready == 0 && unacked == 0) {
        settledSamples++;
        if (settledSamples < 2) {
          sleep(600);
        }
      } else {
        settledSamples = 0;
        sleep(100);
      }
    }
    if (settledSamples < 2) {
      throw new IllegalStateException("rabbit queue never settled: " + queue);
    }
  }

  /** Blocks until a (consumer-less, e.g. DLQ) queue holds exactly depth messages. */
  public void awaitDepth(String queue, int depth) {
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      if (readyCount(queue) == depth) {
        return;
      }
      sleep(100);
    }
    throw new IllegalStateException("rabbit queue " + queue + " never reached depth " + depth);
  }

  /** Purges everything ready then waits out anything in flight, across all four queues. */
  public void drain() {
    List<String> queues =
        List.of(
            QUEUE,
            NotificationQueues.deadLetterQueue(QUEUE),
            NAIVE_QUEUE,
            NotificationQueues.deadLetterQueue(NAIVE_QUEUE));
    long deadline = System.currentTimeMillis() + 30_000;
    while (System.currentTimeMillis() < deadline) {
      boolean settled = true;
      for (String queue : queues) {
        int ready = readyCount(queue);
        int unacked = unackedCount(queue);
        if (ready > 0) {
          try {
            channel.queuePurge(queue);
          } catch (Exception e) {
            throw new IllegalStateException("purge failed for " + queue, e);
          }
        }
        settled = settled && ready == 0 && unacked == 0;
      }
      if (settled) {
        return;
      }
      sleep(100);
    }
    throw new IllegalStateException("rabbit drain never settled");
  }

  private int unackedCount(String queue) {
    try {
      String endpoint = mgmtUrl + "/api/queues/%2F/" + queue;
      String auth =
          Base64.getEncoder()
              .encodeToString((mgmtUser + ":" + mgmtPass).getBytes(StandardCharsets.UTF_8));
      HttpRequest req =
          HttpRequest.newBuilder(URI.create(endpoint))
              .header("Authorization", "Basic " + auth)
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
      if (resp.statusCode() != 200) {
        return 0;
      }
      JsonNode node = mapper.readTree(resp.body());
      JsonNode unacked = node.get("messages_unacknowledged");
      return unacked == null ? 0 : unacked.asInt();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("unacked count interrupted", e);
    } catch (Exception e) {
      return 0;
    }
  }

  private void awaitManagementReady() {
    long deadline = System.currentTimeMillis() + 60_000;
    String auth =
        Base64.getEncoder()
            .encodeToString((mgmtUser + ":" + mgmtPass).getBytes(StandardCharsets.UTF_8));
    while (System.currentTimeMillis() < deadline) {
      try {
        HttpRequest req =
            HttpRequest.newBuilder(URI.create(mgmtUrl + "/api/overview"))
                .header("Authorization", "Basic " + auth)
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 200) {
          return;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("mgmt ready interrupted", e);
      } catch (Exception e) {
        // keep polling
      }
      sleep(250);
    }
    throw new IllegalStateException("rabbit management API never became ready");
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while awaiting rabbit", e);
    }
  }
}
