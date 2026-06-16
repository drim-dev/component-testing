package dev.drim.relay.app;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.seams.AttachmentAccess;
import dev.drim.relay.store.AttachmentRepository;
import dev.drim.relay.store.ChannelMemberRepository;
import dev.drim.relay.store.ChannelRepository;
import dev.drim.relay.web.ApiException;
import org.springframework.stereotype.Component;

/**
 * The correct G-S3 seam: resolve the attachment's channel and require the caller's MEMBERSHIP —
 * never key possession. Unknown id and private-channel non-member both 404 (byte-identical body);
 * public-channel non-member gets 403. The naive variant returns the attachment by id.
 */
@Component
public class CorrectAttachmentAccess implements AttachmentAccess {
  private final AttachmentRepository attachments;
  private final ChannelRepository channels;
  private final ChannelMemberRepository members;

  public CorrectAttachmentAccess(
      AttachmentRepository attachments,
      ChannelRepository channels,
      ChannelMemberRepository members) {
    this.attachments = attachments;
    this.channels = channels;
    this.members = members;
  }

  @Override
  public Entities.Attachment authorize(String attachmentId, String userId) {
    var attachment =
        attachments
            .findById(attachmentId)
            .map(r -> r.toDomain())
            .orElseThrow(NotFounds::attachment);
    if (members.findByChannelIdAndUserId(attachment.channelId(), userId).isPresent()) {
      return attachment;
    }
    boolean isPrivate =
        channels.findById(attachment.channelId()).map(r -> r.isPrivate()).orElse(false);
    if (isPrivate) {
      throw NotFounds.attachment();
    }
    throw ApiException.forbidden(
        "channel:membership_required", "Membership is required to download this attachment.");
  }
}
