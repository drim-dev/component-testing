package dev.drim.relay.app;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.domain.Role;
import dev.drim.relay.id.IdFactory;
import dev.drim.relay.seams.AttachmentAccess;
import dev.drim.relay.seams.AttachmentStore;
import dev.drim.relay.seams.ChannelRoleGate;
import dev.drim.relay.store.AttachmentRepository;
import dev.drim.relay.store.entity.AttachmentRow;
import dev.drim.relay.web.ApiException;
import dev.drim.relay.web.CurrentUser;
import java.io.IOException;
import java.time.Instant;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Attachments: upload (member+, ≤ 1 MiB, non-empty) writes bytes to the object store under an
 * opaque key then records the metadata row; download authorizes via {@link AttachmentAccess} (G-S3
 * — membership, never key possession) and streams the bytes back.
 */
@RestController
public class AttachmentsController {
  private static final long MAX_ATTACHMENT_BYTES = 1L << 20; // 1 MiB

  private final CurrentUser currentUser;
  private final ChannelRoleGate roleGate;
  private final AttachmentAccess attachmentAccess;
  private final AttachmentStore store;
  private final AttachmentRepository attachments;
  private final IdFactory ids;

  public AttachmentsController(
      CurrentUser currentUser,
      ChannelRoleGate roleGate,
      AttachmentAccess attachmentAccess,
      AttachmentStore store,
      AttachmentRepository attachments,
      IdFactory ids) {
    this.currentUser = currentUser;
    this.roleGate = roleGate;
    this.attachmentAccess = attachmentAccess;
    this.store = store;
    this.attachments = attachments;
    this.ids = ids;
  }

  public record AttachmentDto(
      String id, String channelId, String filename, long sizeBytes, Instant createdAt) {}

  @PostMapping("/channels/{id}/attachments")
  public ResponseEntity<AttachmentDto> upload(
      @PathVariable String id, @RequestParam(name = "file", required = false) MultipartFile file) {
    roleGate.authorizeRole(id, currentUser.id(), Role.MEMBER);

    if (file == null || file.isEmpty()) {
      if (file == null) {
        throw ApiException.invalid("attachment:invalid", "A file field is required.");
      }
      throw ApiException.invalid("attachment:empty", "The attachment is empty.");
    }
    if (file.getSize() > MAX_ATTACHMENT_BYTES) {
      throw ApiException.tooLarge(
          "attachment:too_large", "The attachment exceeds the 1 MiB limit.");
    }
    byte[] content;
    try {
      content = file.getBytes();
    } catch (IOException e) {
      throw ApiException.invalid("attachment:invalid", "Could not read the upload.");
    }
    if (content.length > MAX_ATTACHMENT_BYTES) {
      throw ApiException.tooLarge(
          "attachment:too_large", "The attachment exceeds the 1 MiB limit.");
    }
    if (content.length == 0) {
      throw ApiException.invalid("attachment:empty", "The attachment is empty.");
    }

    String attachmentId = ids.create();
    String storageKey = id + "/" + attachmentId;
    store.put(storageKey, content);
    AttachmentRow row =
        new AttachmentRow(
            attachmentId,
            id,
            currentUser.id(),
            null,
            file.getOriginalFilename(),
            content.length,
            storageKey,
            Instant.now());
    attachments.save(row);

    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new AttachmentDto(
                row.getId(),
                row.getChannelId(),
                row.getFilename(),
                row.getSizeBytes(),
                row.getCreatedAt()));
  }

  @GetMapping("/attachments/{id}")
  public ResponseEntity<Resource> download(@PathVariable String id) {
    Entities.Attachment attachment = attachmentAccess.authorize(id, currentUser.id());
    byte[] content = store.get(attachment.storageKey());
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(attachment.filename()).build().toString())
        .body(new ByteArrayResource(content));
  }
}
