package dev.drim.relay.infra;

import dev.drim.relay.domain.Events;
import dev.drim.relay.presence.grpc.GetPresenceRequest;
import dev.drim.relay.presence.grpc.PresenceGrpc;
import dev.drim.relay.presence.grpc.PresenceStatus;
import dev.drim.relay.presence.grpc.StreamChannelPresenceRequest;
import dev.drim.relay.seams.PresenceClient;
import dev.drim.relay.seams.Seams.PresenceResult;
import io.grpc.Channel;
import io.grpc.StatusRuntimeException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The correct G-GRPC seam: it consumes the server stream to its CLEAN end-of-stream and reports a
 * mid-stream transport error as {@code incomplete} — so the handler returns 502 and NEVER a partial
 * member list as complete. The naive variant swallows the error and returns whatever arrived.
 * Mirrors go/src/relay/presence/client.go.
 *
 * <p>With the blocking stub the stream is an {@link Iterator}: a clean end is {@code hasNext()} ==
 * false; a mid-stream abort surfaces as a {@link StatusRuntimeException} on iteration.
 */
@Component
public class GrpcPresenceClient implements PresenceClient {
  private final PresenceGrpc.PresenceBlockingStub stub;

  public GrpcPresenceClient(Channel channel) {
    this.stub = PresenceGrpc.newBlockingStub(channel);
  }

  @Override
  public boolean userPresence(String userId) {
    PresenceStatus status =
        stub.getPresence(GetPresenceRequest.newBuilder().setUserId(userId).build());
    return status.getOnline();
  }

  @Override
  public PresenceResult channelPresence(List<String> userIds) {
    Iterator<PresenceStatus> stream =
        stub.streamChannelPresence(
            StreamChannelPresenceRequest.newBuilder().addAllUserIds(userIds).build());
    List<Events.PresenceStatus> statuses = new ArrayList<>();
    try {
      while (stream.hasNext()) {
        PresenceStatus msg = stream.next();
        statuses.add(new Events.PresenceStatus(msg.getUserId(), msg.getOnline()));
      }
    } catch (StatusRuntimeException e) {
      // A mid-stream abort means we did NOT reach clean end-of-stream: the list we hold is
      // partial. Surfacing it as complete is the gallery bug — report it incomplete.
      return new PresenceResult(List.of(), true);
    }
    return new PresenceResult(statuses, false);
  }
}
