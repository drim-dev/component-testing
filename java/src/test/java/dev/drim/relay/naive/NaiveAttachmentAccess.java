package dev.drim.relay.naive;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.seams.AttachmentAccess;
import dev.drim.relay.store.AttachmentRepository;
import dev.drim.relay.web.ApiException;

/**
 * The G-S3 naive variant (exhibit, NEVER in src/): it returns the attachment BY ID, authorizing on
 * key/id possession instead of channel membership — so a non-member downloads a private channel's
 * attachment (200 + bytes) instead of the existence-hiding 404. Same family as G-IDOR, different
 * dependency. Caught by AttachmentsTest S-AT-06 + GS3NaiveDemoTest.
 */
public final class NaiveAttachmentAccess implements AttachmentAccess {
  private final AttachmentRepository attachments;

  public NaiveAttachmentAccess(AttachmentRepository attachments) {
    this.attachments = attachments;
  }

  @Override
  public Entities.Attachment authorize(String attachmentId, String userId) {
    // The bug: membership is never checked — possessing the id is treated as authorization.
    return attachments
        .findById(attachmentId)
        .map(r -> r.toDomain())
        .orElseThrow(() -> ApiException.notFound("attachment:not_found", "Attachment not found."));
  }
}
