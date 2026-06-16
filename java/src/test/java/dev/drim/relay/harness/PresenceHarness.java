package dev.drim.relay.harness;

import dev.drim.relay.infra.PresenceKeys;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Boots the REAL companion-owned presence gRPC service on an ephemeral 127.0.0.1 port over a real
 * socket, so the API consumes it through genuine gRPC (cleartext h2c) — the transport-agnostic
 * proof (not an in-process double). It shares the suite's Redis so a heartbeat is observable
 * through the stream. Seed = set presence keys directly; fault control = arm the stream to fail
 * after N (the deterministic partial-stream probe); Reset = clear the fault flag (presence keys are
 * cleared by the suite's Redis FLUSHDB). Mirrors go/harness/presence.go.
 */
public final class PresenceHarness implements DependencyHarness {
  private final String redisHost;
  private final int redisPort;
  private final StreamFault fault = new StreamFault();

  private RedisClient redisClient;
  private StatefulRedisConnection<String, String> redisConn;
  private Server server;
  private int port;

  /**
   * Builds the harness against the suite's Redis address (known only after the Redis harness starts
   * — an honest dependency between harnesses).
   */
  public PresenceHarness(String redisHost, int redisPort) {
    this.redisHost = redisHost;
    this.redisPort = redisPort;
  }

  @Override
  public void start() {
    redisClient = RedisClient.create(RedisURI.create(redisHost, redisPort));
    redisConn = redisClient.connect();
    try {
      server =
          NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
              .addService(new PresenceService(redisConn.sync(), fault))
              .build()
              .start();
    } catch (IOException e) {
      throw new IllegalStateException("presence gRPC server start failed", e);
    }
    port = server.getPort();
  }

  /** The gRPC target the app's presence channel dials. */
  public String target() {
    return "127.0.0.1:" + port;
  }

  @Override
  public void reset() {
    fault.clear();
  }

  @Override
  public void stop() {
    if (server != null) {
      server.shutdownNow();
    }
    if (redisConn != null) {
      redisConn.close();
    }
    if (redisClient != null) {
      redisClient.shutdown();
    }
  }

  /** Marks a user online directly (the same key the heartbeat writes), 60 s TTL. */
  public void setOnline(String userId) {
    redisConn.sync().setex(PresenceKeys.KEY_PREFIX + userId, 60, "1");
  }

  /** Arms the partial-stream fault: the next stream emits n statuses then aborts. */
  public void failStreamAfter(int n) {
    fault.failAfter(n);
  }
}
