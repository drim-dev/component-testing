package dev.drim.relay.app;

import dev.drim.relay.domain.Events;
import dev.drim.relay.domain.Previews;
import dev.drim.relay.seams.MessagePostedPublisher;
import dev.drim.relay.store.AttachmentRepository;
import dev.drim.relay.store.ChannelMessageRepository;
import dev.drim.relay.store.entity.ChannelMessageRow;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The pinned channel-message write (02-api.md §3, no outbox): one transaction inserts the message,
 * binds its attachments, and publishes the Kafka event AWAITING broker confirmation — then commits.
 * A publish failure throws (→ 503) and the transaction rolls back, so the message is never
 * half-posted (G-KAFKA producer). A separate bean so {@code @Transactional} crosses a real Spring
 * proxy boundary; self-invocation from {@link ChannelsMessageController} would silently bypass it.
 */
@Component
public class ChannelMessageWriter {
  private final ChannelMessageRepository messages;
  private final AttachmentRepository attachments;
  private final MessagePostedPublisher publisher;

  public ChannelMessageWriter(
      ChannelMessageRepository messages,
      AttachmentRepository attachments,
      MessagePostedPublisher publisher) {
    this.messages = messages;
    this.attachments = attachments;
    this.publisher = publisher;
  }

  @Transactional
  public void post(ChannelMessageRow message, List<String> attachmentIds) {
    messages.saveAndFlush(message);
    if (!attachmentIds.isEmpty()) {
      attachments.bindToMessage(message.getId(), attachmentIds);
    }
    publisher.publish(
        new Events.MessagePosted(
            message.getId(),
            message.getChannelId(),
            message.getSenderId(),
            Previews.preview(message.getText()),
            message.getCreatedAt()));
  }
}
