package dev.drim.relay.seams;

import dev.drim.relay.domain.Entities;

/**
 * The G-S3 seam: download authorization derives from the attachment's CHANNEL MEMBERSHIP, never
 * from possession of the id or storage key. Unknown id and private-channel non-member return the
 * same existence-hiding 404; public non-member → 403. The naive variant looks the attachment up by
 * id and returns it (possession IS access).
 */
public interface AttachmentAccess {
  Entities.Attachment authorize(String attachmentId, String userId);
}
