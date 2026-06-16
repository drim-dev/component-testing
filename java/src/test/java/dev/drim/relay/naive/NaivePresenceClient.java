package dev.drim.relay.naive;

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

/**
 * The G-GRPC naive variant (exhibit, NEVER in src/): it SWALLOWS a mid-stream RpcException in a
 * try/catch and returns whatever messages arrived BEFORE the abort, marked complete — a truncated
 * stream reported as a full member list. The correct client reports incomplete → 502. Caught by
 * PresenceTest S-PR-04 + GGrpcNaiveDemoTest.
 */
public final class NaivePresenceClient implements PresenceClient {
  private final PresenceGrpc.PresenceBlockingStub stub;

  public NaivePresenceClient(Channel channel) {
    this.stub = PresenceGrpc.newBlockingStub(channel);
  }

  @Override
  public boolean userPresence(String userId) {
    return stub.getPresence(GetPresenceRequest.newBuilder().setUserId(userId).build()).getOnline();
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
    } catch (StatusRuntimeException swallowed) {
      // The bug: the partial list is returned as COMPLETE (incomplete=false) instead of surfacing
      // the truncation. The handler then reports a short member list as a successful 200.
    }
    return new PresenceResult(statuses, false);
  }
}
