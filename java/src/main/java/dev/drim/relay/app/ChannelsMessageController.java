package dev.drim.relay.app;

import dev.drim.relay.app.ChannelDtos.ChannelMessageDto;
import dev.drim.relay.domain.Previews;
import dev.drim.relay.domain.Role;
import dev.drim.relay.id.IdFactory;
import dev.drim.relay.seams.ChannelRoleGate;
import dev.drim.relay.seams.LinkPreviewer;
import dev.drim.relay.store.AttachmentRepository;
import dev.drim.relay.store.entity.ChannelMessageRow;
import dev.drim.relay.web.ApiException;
import dev.drim.relay.web.CurrentUser;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Posting a channel message: role gate (member+), validation, attachment ownership check, a bounded
 * graceful unfurl (G-HTTP) BEFORE the write so the title persists with the row, then the pinned
 * insert→publish→commit ordering via {@link ChannelMessageWriter}.
 */
@RestController
public class ChannelsMessageController {
  private static final int MAX_TEXT = 4000;
  private static final int MAX_ATTACHMENTS = 10;

  private final CurrentUser currentUser;
  private final ChannelRoleGate roleGate;
  private final AttachmentRepository attachments;
  private final LinkPreviewer linkPreviewer;
  private final ChannelMessageWriter writer;
  private final IdFactory ids;

  public ChannelsMessageController(
      CurrentUser currentUser,
      ChannelRoleGate roleGate,
      AttachmentRepository attachments,
      LinkPreviewer linkPreviewer,
      ChannelMessageWriter writer,
      IdFactory ids) {
    this.currentUser = currentUser;
    this.roleGate = roleGate;
    this.attachments = attachments;
    this.linkPreviewer = linkPreviewer;
    this.writer = writer;
    this.ids = ids;
  }

  public record PostMessageBody(String text, List<String> attachmentIds) {}

  @PostMapping("/channels/{id}/messages")
  public ResponseEntity<ChannelMessageDto> post(
      @PathVariable String id, @RequestBody(required = false) PostMessageBody body) {
    roleGate.authorizeRole(id, currentUser.id(), Role.MEMBER);

    String text = body == null ? null : body.text();
    List<String> attachmentIds =
        body == null || body.attachmentIds() == null ? List.of() : body.attachmentIds();

    if (text == null || text.isEmpty() || text.codePointCount(0, text.length()) > MAX_TEXT) {
      throw ApiException.invalid("message:text:invalid", "text must be 1–4000 chars.");
    }
    if (attachmentIds.size() > MAX_ATTACHMENTS) {
      throw ApiException.invalid(
          "message:attachment:invalid", "A message can reference at most 10 attachments.");
    }
    validateAttachments(id, currentUser.id(), attachmentIds);

    String title = null;
    String url = Previews.firstUrl(text);
    if (url != null) {
      title = linkPreviewer.preview(url).orElse(null);
    }

    ChannelMessageRow message =
        new ChannelMessageRow(ids.create(), id, currentUser.id(), text, title, Instant.now());
    writer.post(message, attachmentIds);

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ChannelMessageDto.of(message.toDomain(), attachmentIds));
  }

  /**
   * Enforces S-AT-04: every referenced attachment must be uploaded by the caller to THIS channel. A
   * bad reference is 422 before any write.
   */
  private void validateAttachments(String channelId, String callerId, List<String> ids) {
    if (ids.isEmpty()) {
      return;
    }
    List<String> owned = attachments.ownedInChannel(channelId, callerId, ids);
    if (owned.size() != ids.size()) {
      throw ApiException.invalid(
          "message:attachment:invalid",
          "Attachments must be uploaded to this channel by you and not already referenced.");
    }
  }
}
