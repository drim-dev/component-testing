package dev.drim.relay.harness;

import dev.drim.relay.presence.grpc.GetPresenceRequest;
import dev.drim.relay.presence.grpc.PresenceGrpc;
import dev.drim.relay.presence.grpc.PresenceStatus;
import dev.drim.relay.presence.grpc.StreamChannelPresenceRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A canned-response stub for the neighbour presence service: answers the unary and streaming RPCs
 * from an in-memory online set, with a test-only {@link StreamFault} that aborts the stream after N
 * messages (the deterministic partial-stream probe for G-GRPC). No Redis, no neighbour dependencies
 * — just the contract the Relay API consumes. Presence is a neighbour service, so in a component
 * test of the Relay API it is stubbed, not run for real.
 */
public final class PresenceService extends PresenceGrpc.PresenceImplBase {
  private final Set<String> online = ConcurrentHashMap.newKeySet();
  private final StreamFault fault;

  public PresenceService(StreamFault fault) {
    this.fault = fault;
  }

  /** Programs the stub: mark a user online in its canned answer. */
  public void setOnline(String userId) {
    online.add(userId);
  }

  /** Clears the online set (the stub owns no shared store to flush). */
  public void clearOnline() {
    online.clear();
  }

  @Override
  public void getPresence(
      GetPresenceRequest request, StreamObserver<PresenceStatus> responseObserver) {
    responseObserver.onNext(
        PresenceStatus.newBuilder()
            .setUserId(request.getUserId())
            .setOnline(online.contains(request.getUserId()))
            .build());
    responseObserver.onCompleted();
  }

  @Override
  public void streamChannelPresence(
      StreamChannelPresenceRequest request, StreamObserver<PresenceStatus> responseObserver) {
    StreamFault.Limit limit = fault.limit();
    List<String> userIds = request.getUserIdsList();
    for (int i = 0; i < userIds.size(); i++) {
      if (limit.armed() && i >= limit.value()) {
        responseObserver.onError(
            Status.UNAVAILABLE
                .withDescription("presence stream fault (test-only): aborting mid-stream")
                .asRuntimeException());
        return;
      }
      responseObserver.onNext(
          PresenceStatus.newBuilder()
              .setUserId(userIds.get(i))
              .setOnline(online.contains(userIds.get(i)))
              .build());
    }
    responseObserver.onCompleted();
  }
}
