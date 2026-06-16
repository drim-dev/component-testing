package dev.drim.relay.harness;

import dev.drim.relay.infra.PresenceKeys;
import dev.drim.relay.presence.grpc.GetPresenceRequest;
import dev.drim.relay.presence.grpc.PresenceGrpc;
import dev.drim.relay.presence.grpc.PresenceStatus;
import dev.drim.relay.presence.grpc.StreamChannelPresenceRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.List;

/**
 * The companion-owned presence gRPC service over Redis (the real transport the G-GRPC catch
 * exercises). The unary RPC reads one key; the streaming RPC emits exactly one status per requested
 * user then closes cleanly — unless the {@link StreamFault} is armed, in which case it emits N
 * statuses then aborts mid-stream. Mirrors presence.Service in go/src/relay/presence/service.go.
 */
public final class PresenceService extends PresenceGrpc.PresenceImplBase {
  private final RedisCommands<String, String> redis;
  private final StreamFault fault;

  public PresenceService(RedisCommands<String, String> redis, StreamFault fault) {
    this.redis = redis;
    this.fault = fault;
  }

  @Override
  public void getPresence(
      GetPresenceRequest request, StreamObserver<PresenceStatus> responseObserver) {
    boolean online = online(request.getUserId());
    responseObserver.onNext(
        PresenceStatus.newBuilder().setUserId(request.getUserId()).setOnline(online).build());
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
      boolean online = online(userIds.get(i));
      responseObserver.onNext(
          PresenceStatus.newBuilder().setUserId(userIds.get(i)).setOnline(online).build());
    }
    responseObserver.onCompleted();
  }

  private boolean online(String userId) {
    return redis.exists(PresenceKeys.KEY_PREFIX + userId) > 0;
  }
}
