package dev.drim.relay.app;

import dev.drim.relay.app.DmDtos.ConversationDto;
import dev.drim.relay.app.DmDtos.DmMessageDto;
import dev.drim.relay.domain.Entities;
import dev.drim.relay.domain.Events;
import dev.drim.relay.domain.Previews;
import dev.drim.relay.id.IdFactory;
import dev.drim.relay.seams.ConversationWriter;
import dev.drim.relay.seams.DmAccess;
import dev.drim.relay.seams.NotificationJobs;
import dev.drim.relay.seams.Seams.ConversationCreateResult;
import dev.drim.relay.store.ConversationRepository;
import dev.drim.relay.store.DmMessageRepository;
import dev.drim.relay.store.UserRepository;
import dev.drim.relay.store.entity.DmMessageRow;
import dev.drim.relay.web.ApiException;
import dev.drim.relay.web.CurrentUser;
import dev.drim.relay.web.Paging;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DmController {
  private final CurrentUser currentUser;
  private final UserRepository users;
  private final ConversationRepository conversations;
  private final DmMessageRepository dmMessages;
  private final DmAccess dmAccess;
  private final ConversationWriter conversationWriter;
  private final NotificationJobs jobs;
  private final IdFactory ids;

  public DmController(
      CurrentUser currentUser,
      UserRepository users,
      ConversationRepository conversations,
      DmMessageRepository dmMessages,
      DmAccess dmAccess,
      ConversationWriter conversationWriter,
      NotificationJobs jobs,
      IdFactory ids) {
    this.currentUser = currentUser;
    this.users = users;
    this.conversations = conversations;
    this.dmMessages = dmMessages;
    this.dmAccess = dmAccess;
    this.conversationWriter = conversationWriter;
    this.jobs = jobs;
    this.ids = ids;
  }

  public record CreateConversationBody(String recipientId) {}

  public record CreateMessageBody(String text) {}

  @PostMapping("/dm/conversations")
  public ResponseEntity<ConversationDto> createConversation(
      @RequestBody(required = false) CreateConversationBody body) {
    String callerId = currentUser.id();
    String recipientId = body == null ? null : body.recipientId();

    if (callerId.equals(recipientId)) {
      throw ApiException.invalid(
          "dm:recipient:self", "You cannot open a conversation with yourself.");
    }
    if (recipientId == null || users.findById(recipientId).isEmpty()) {
      throw ApiException.notFound("user:not_found", "User not found.");
    }

    String[] pair = Entities.normalizePair(callerId, recipientId);
    ConversationCreateResult result = conversationWriter.create(pair[0], pair[1]);
    HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
    return ResponseEntity.status(status).body(ConversationDto.of(result.conversation()));
  }

  @GetMapping("/dm/conversations")
  public Paging.Page<ConversationDto> listConversations(
      @RequestParam(required = false) String limit, @RequestParam(required = false) String before) {
    int parsedLimit = Paging.parseLimit(limit);
    if (before != null && !before.isEmpty() && !conversations.existsById(before)) {
      throw Paging.unknownBefore();
    }
    List<ConversationDto> rows =
        conversations
            .pageForUser(currentUser.id(), emptyToNull(before), fetchLimit(parsedLimit))
            .stream()
            .map(r -> ConversationDto.of(r.toDomain()))
            .toList();
    return Paging.build(rows, parsedLimit, ConversationDto::id);
  }

  @GetMapping("/dm/conversations/{id}")
  public ConversationDto getConversation(@PathVariable String id) {
    return dmAccess
        .getForParticipant(id, currentUser.id())
        .map(ConversationDto::of)
        .orElseThrow(NotFounds::conversation);
  }

  @PostMapping("/dm/conversations/{id}/messages")
  public ResponseEntity<DmMessageDto> createMessage(
      @PathVariable String id, @RequestBody(required = false) CreateMessageBody body) {
    Entities.Conversation conv =
        dmAccess.getForParticipant(id, currentUser.id()).orElseThrow(NotFounds::conversation);

    String text = body == null ? null : body.text();
    if (text == null || text.isEmpty() || text.codePointCount(0, text.length()) > 4000) {
      throw ApiException.invalid("message:text:invalid", "text must be 1–4000 chars.");
    }

    DmMessageRow row = new DmMessageRow(ids.create(), id, currentUser.id(), text, Instant.now());
    dmMessages.save(row);

    // Pinned ordering (02-api.md §2): the notification job is enqueued AFTER the message commits,
    // awaiting the broker's publisher confirmation. A publish failure here is a 500 — the message
    // stays. This avoids the worker racing an uncommitted dm_messages row into its FK.
    String recipient = conv.userLo().equals(currentUser.id()) ? conv.userHi() : conv.userLo();
    jobs.enqueue(
        new Events.NotificationJob(
            row.getId(), id, currentUser.id(), recipient, Previews.preview(text)));

    return ResponseEntity.status(HttpStatus.CREATED).body(DmMessageDto.of(row.toDomain()));
  }

  @GetMapping("/dm/conversations/{id}/messages")
  public Paging.Page<DmMessageDto> listMessages(
      @PathVariable String id,
      @RequestParam(required = false) String limit,
      @RequestParam(required = false) String before) {
    dmAccess.getForParticipant(id, currentUser.id()).orElseThrow(NotFounds::conversation);

    int parsedLimit = Paging.parseLimit(limit);
    if (before != null
        && !before.isEmpty()
        && !dmMessages.existsByConversationIdAndId(id, before)) {
      throw Paging.unknownBefore();
    }
    List<DmMessageDto> rows =
        dmMessages.page(id, emptyToNull(before), fetchLimit(parsedLimit)).stream()
            .map(r -> DmMessageDto.of(r.toDomain()))
            .toList();
    return Paging.build(rows, parsedLimit, DmMessageDto::id);
  }

  private static String emptyToNull(String s) {
    return (s == null || s.isEmpty()) ? null : s;
  }

  /** Fetch limit+1 so {@link Paging#build} can detect whether a next page exists. */
  private static org.springframework.data.domain.Limit fetchLimit(int limit) {
    return org.springframework.data.domain.Limit.of(limit + 1);
  }
}
