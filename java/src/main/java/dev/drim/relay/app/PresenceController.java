package dev.drim.relay.app;

import dev.drim.relay.seams.ChannelReadGate;
import dev.drim.relay.seams.Heartbeats;
import dev.drim.relay.seams.PresenceClient;
import dev.drim.relay.seams.Seams.PresenceResult;
import dev.drim.relay.store.ChannelMemberRepository;
import dev.drim.relay.store.UserRepository;
import dev.drim.relay.web.ApiException;
import dev.drim.relay.web.CurrentUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Presence (02-api.md §7–9): a heartbeat marks the caller online; per-user presence is a unary
 * lookup; channel presence consumes the gRPC stream (G-GRPC) — a mid-stream error surfaces as 502
 * via {@link PresenceResult#incomplete()}, never a partial list reported as complete.
 */
@RestController
public class PresenceController {
  private final CurrentUser currentUser;
  private final Heartbeats heartbeats;
  private final PresenceClient presence;
  private final UserRepository users;
  private final ChannelMemberRepository members;
  private final ChannelReadGate readGate;

  public PresenceController(
      CurrentUser currentUser,
      Heartbeats heartbeats,
      PresenceClient presence,
      UserRepository users,
      ChannelMemberRepository members,
      ChannelReadGate readGate) {
    this.currentUser = currentUser;
    this.heartbeats = heartbeats;
    this.presence = presence;
    this.users = users;
    this.members = members;
    this.readGate = readGate;
  }

  @PostMapping("/me/heartbeat")
  public ResponseEntity<Void> heartbeat() {
    heartbeats.mark(currentUser.id());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/users/{id}/presence")
  public Map<String, String> userPresence(@PathVariable String id) {
    if (users.findById(id).isEmpty()) {
      throw ApiException.notFound("user:not_found", "User not found.");
    }
    return Map.of("userId", id, "status", statusOf(presence.userPresence(id)));
  }

  @GetMapping("/channels/{id}/presence")
  public Map<String, Object> channelPresence(@PathVariable String id) {
    readGate.authorizeRead(id, currentUser.id(), true);

    List<String> memberIds = members.memberIds(id);
    memberIds.sort(String::compareTo);

    PresenceResult result = presence.channelPresence(memberIds);
    if (result.incomplete()) {
      throw ApiException.upstream(
          "presence:incomplete", "The presence stream terminated before completion.");
    }
    List<Map<String, String>> statuses = new ArrayList<>(result.statuses().size());
    for (var s : result.statuses()) {
      statuses.add(Map.of("userId", s.userId(), "status", statusOf(s.online())));
    }
    return Map.of("members", statuses);
  }

  private static String statusOf(boolean online) {
    return online ? "online" : "offline";
  }
}
