package dev.drim.relay.harness;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.net.InetSocketAddress;

/**
 * Boots a STUB presence gRPC service on an ephemeral 127.0.0.1 port over a real socket, so the API
 * still consumes presence through genuine gRPC (cleartext h2c) — the transport-agnostic proof —
 * without dragging the neighbour's own dependencies (its Redis) into the test. Presence is a
 * NEIGHBOUR service, so in a component test of the Relay API it is stubbed, not run for real.
 * setOnline = program the canned answer; fault control = arm the stream to fail after N (the
 * deterministic partial-stream probe); reset = clear the online set and the fault flag.
 */
public final class PresenceHarness implements DependencyHarness {
  private final StreamFault fault = new StreamFault();
  private final PresenceService service = new PresenceService(fault);

  private Server server;
  private int port;

  @Override
  public void start() {
    try {
      server =
          NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
              .addService(service)
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
    service.clearOnline();
  }

  @Override
  public void stop() {
    if (server != null) {
      server.shutdownNow();
    }
  }

  /** Programs the stub: mark a user online in its canned answer. */
  public void setOnline(String userId) {
    service.setOnline(userId);
  }

  /** Arms the partial-stream fault: the next stream emits n statuses then aborts. */
  public void failStreamAfter(int n) {
    fault.failAfter(n);
  }
}
