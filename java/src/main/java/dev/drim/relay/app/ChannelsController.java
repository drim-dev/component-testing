package dev.drim.relay.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.drim.relay.app.ChannelDtos.ChannelDto;
import dev.drim.relay.app.ChannelDtos.ChannelMessageDto;
import dev.drim.relay.app.ChannelDtos.MembershipDto;
import dev.drim.relay.domain.Entities;
import dev.drim.relay.domain.Role;
import dev.drim.relay.id.IdFactory;
import dev.drim.relay.seams.ChannelReadGate;
import dev.drim.relay.seams.ChannelRoleGate;
import dev.drim.relay.seams.MembershipCache;
import dev.drim.relay.seams.MembershipWriter;
import dev.drim.relay.seams.UnreadCounters;
import dev.drim.relay.store.ChannelMemberRepository;
import dev.drim.relay.store.ChannelMessageRepository;
import dev.drim.relay.store.ChannelRepository;
import dev.drim.relay.store.UserRepository;
import dev.drim.relay.store.entity.ChannelMemberRow;
import dev.drim.relay.store.entity.ChannelRow;
import dev.drim.relay.web.ApiException;
import dev.drim.relay.web.CurrentUser;
import dev.drim.relay.web.Paging;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Channels: create/list/get + membership (join/add/promote/remove/leave/delete) + message read +
 * mark-read. The authorization decisions live behind {@link ChannelReadGate} (G-BOLA-READ) and
 * {@link ChannelRoleGate} (G-BOLA-ROLE); membership writes go through {@link MembershipWriter}
 * (G-CACHE). The handlers translate seam results into the pinned status codes.
 */
@RestController
public class ChannelsController {
  private final CurrentUser currentUser;
  private final ChannelRepository channels;
  private final ChannelMemberRepository members;
  private final ChannelMessageRepository messages;
  private final UserRepository users;
  private final ChannelReadGate readGate;
  private final ChannelRoleGate roleGate;
  private final MembershipWriter membershipWriter;
  private final MembershipCache cache;
  private final UnreadCounters unread;
  private final IdFactory ids;

  public ChannelsController(
      CurrentUser currentUser,
      ChannelRepository channels,
      ChannelMemberRepository members,
      ChannelMessageRepository messages,
      UserRepository users,
      ChannelReadGate readGate,
      ChannelRoleGate roleGate,
      MembershipWriter membershipWriter,
      MembershipCache cache,
      UnreadCounters unread,
      IdFactory ids) {
    this.currentUser = currentUser;
    this.channels = channels;
    this.members = members;
    this.messages = messages;
    this.users = users;
    this.readGate = readGate;
    this.roleGate = roleGate;
    this.membershipWriter = membershipWriter;
    this.cache = cache;
    this.unread = unread;
    this.ids = ids;
  }

  public record CreateChannelBody(String name, @JsonProperty("private") boolean isPrivate) {}

  public record AddMemberBody(String userId) {}

  @PostMapping("/channels")
  public ResponseEntity<ChannelDto> create(@RequestBody(required = false) CreateChannelBody body) {
    String name = body == null ? null : body.name();
    boolean isPrivate = body != null && body.isPrivate();
    if (name == null || name.isEmpty() || name.codePointCount(0, name.length()) > 100) {
      throw ApiException.invalid("channel:name:invalid", "name must be 1–100 chars.");
    }

    ChannelRow channel = new ChannelRow(ids.create(), name, isPrivate, Instant.now());
    channels.save(channel);
    members.save(
        new ChannelMemberRow(channel.getId(), currentUser.id(), Role.OWNER, Instant.now()));
    return ResponseEntity.status(HttpStatus.CREATED).body(ChannelDto.created(channel.toDomain()));
  }

  @GetMapping("/channels")
  public Paging.Page<ChannelDto> list(
      @RequestParam(required = false) String limit, @RequestParam(required = false) String before) {
    int parsedLimit = Paging.parseLimit(limit);
    if (before != null && !before.isEmpty() && !channels.existsById(before)) {
      throw Paging.unknownBefore();
    }
    List<ChannelDto> rows =
        channels
            .pageVisible(currentUser.id(), emptyToNull(before), fetchLimit(parsedLimit))
            .stream()
            .map(r -> ChannelDto.withCount(r.toDomain(), members.countByChannelId(r.getId())))
            .toList();
    return Paging.build(rows, parsedLimit, ChannelDto::id);
  }

  @GetMapping("/channels/{id}")
  public ChannelDto get(@PathVariable String id) {
    Entities.Channel channel = readGate.authorizeRead(id, currentUser.id(), false);
    return ChannelDto.withCount(channel, members.countByChannelId(id));
  }

  @PostMapping("/channels/{id}/join")
  public ResponseEntity<MembershipDto> join(@PathVariable String id) {
    Entities.Channel channel =
        channels.findById(id).map(r -> r.toDomain()).orElseThrow(NotFounds::channel);
    if (members.findByChannelIdAndUserId(id, currentUser.id()).isPresent()) {
      throw ApiException.conflict("channel:member:already", "You are already a member.");
    }
    if (channel.isPrivate()) {
      throw NotFounds.channel();
    }
    Instant joinedAt = Instant.now();
    membershipWriter.add(new Entities.ChannelMember(id, currentUser.id(), Role.MEMBER, joinedAt));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new MembershipDto(id, currentUser.id(), Role.MEMBER.wire(), joinedAt));
  }

  @PostMapping("/channels/{id}/members")
  public ResponseEntity<MembershipDto> addMember(
      @PathVariable String id, @RequestBody(required = false) AddMemberBody body) {
    roleGate.authorizeRole(id, currentUser.id(), Role.ADMIN);
    String targetId = body == null ? null : body.userId();
    if (targetId == null || users.findById(targetId).isEmpty()) {
      throw ApiException.notFound("user:not_found", "User not found.");
    }
    if (members.findByChannelIdAndUserId(id, targetId).isPresent()) {
      throw ApiException.conflict("channel:member:already", "That user is already a member.");
    }
    Instant joinedAt = Instant.now();
    membershipWriter.add(new Entities.ChannelMember(id, targetId, Role.MEMBER, joinedAt));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(new MembershipDto(id, targetId, Role.MEMBER.wire(), joinedAt));
  }

  @PostMapping("/channels/{id}/members/{userId}/promote")
  public MembershipDto promote(@PathVariable String id, @PathVariable String userId) {
    roleGate.authorizeRole(id, currentUser.id(), Role.OWNER);
    Entities.ChannelMember target =
        members.findByChannelIdAndUserId(id, userId).map(r -> r.toDomain()).orElse(null);
    if (target == null) {
      throw ApiException.notFound("channel:member:not_found", "Member not found.");
    }
    if (target.role().atLeast(Role.ADMIN)) {
      throw ApiException.conflict(
          "channel:member:already", "That member is already an admin or owner.");
    }
    ChannelMemberRow row = members.findByChannelIdAndUserId(id, userId).orElseThrow();
    row.setRole(Role.ADMIN);
    members.save(row);
    return new MembershipDto(id, userId, Role.ADMIN.wire(), target.joinedAt());
  }

  @DeleteMapping("/channels/{id}/members/{userId}")
  public ResponseEntity<Void> removeMember(@PathVariable String id, @PathVariable String userId) {
    String callerId = currentUser.id();
    if (userId.equals(callerId)) {
      return leave(id, callerId);
    }
    return kick(id, callerId, userId);
  }

  private ResponseEntity<Void> leave(String channelId, String callerId) {
    Entities.Channel channel =
        channels.findById(channelId).map(r -> r.toDomain()).orElseThrow(NotFounds::channel);
    Entities.ChannelMember member =
        members.findByChannelIdAndUserId(channelId, callerId).map(r -> r.toDomain()).orElse(null);
    if (member == null) {
      if (channel.isPrivate()) {
        throw NotFounds.channel();
      }
      throw ApiException.notFound("channel:member:not_found", "Member not found.");
    }
    if (member.role() == Role.OWNER) {
      throw ApiException.conflict(
          "channel:owner:cannot_leave", "The owner cannot leave their own channel.");
    }
    membershipWriter.remove(channelId, callerId);
    return ResponseEntity.noContent().build();
  }

  private ResponseEntity<Void> kick(String channelId, String callerId, String targetId) {
    Entities.ChannelMember caller = roleGate.authorizeRole(channelId, callerId, Role.ADMIN);
    Entities.ChannelMember target =
        members.findByChannelIdAndUserId(channelId, targetId).map(r -> r.toDomain()).orElse(null);
    if (target == null) {
      throw ApiException.notFound("channel:member:not_found", "Member not found.");
    }
    boolean kickingPrivileged =
        target.role() == Role.OWNER || (target.role() == Role.ADMIN && caller.role() != Role.OWNER);
    if (kickingPrivileged) {
      throw ApiException.forbidden(
          "channel:role:forbidden", "Your role does not permit removing this member.");
    }
    membershipWriter.remove(channelId, targetId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/channels/{id}")
  public ResponseEntity<Void> delete(@PathVariable String id) {
    roleGate.authorizeRole(id, currentUser.id(), Role.OWNER);
    channels.deleteById(id);
    cache.invalidate(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/channels/{id}/messages")
  public Paging.Page<ChannelMessageDto> messages(
      @PathVariable String id,
      @RequestParam(required = false) String limit,
      @RequestParam(required = false) String before) {
    readGate.authorizeRead(id, currentUser.id(), true);
    int parsedLimit = Paging.parseLimit(limit);
    if (before != null && !before.isEmpty() && !messages.existsByChannelIdAndId(id, before)) {
      throw Paging.unknownBefore();
    }
    List<ChannelMessageDto> rows =
        messages.page(id, emptyToNull(before), fetchLimit(parsedLimit)).stream()
            .map(r -> ChannelMessageDto.of(r.toDomain(), null))
            .toList();
    return Paging.build(rows, parsedLimit, ChannelMessageDto::id);
  }

  @PostMapping("/channels/{id}/read")
  public ResponseEntity<Void> markRead(@PathVariable String id) {
    readGate.authorizeRead(id, currentUser.id(), true);
    unread.reset(currentUser.id(), id);
    return ResponseEntity.noContent().build();
  }

  private static String emptyToNull(String s) {
    return (s == null || s.isEmpty()) ? null : s;
  }

  private static Limit fetchLimit(int limit) {
    return Limit.of(limit + 1);
  }
}
