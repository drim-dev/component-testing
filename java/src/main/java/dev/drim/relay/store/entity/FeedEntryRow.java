package dev.drim.relay.store.entity;

import dev.drim.relay.domain.Entities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "feed_entries")
public class FeedEntryRow {
  @Id private String id;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "channel_id", nullable = false)
  private String channelId;

  // Deliberately NO FK on message_id (03-schema.md): publish-confirmed-then-commit means the
  // projection may momentarily lead channel_messages.
  @Column(name = "message_id", nullable = false)
  private String messageId;

  @Column(name = "sender_id", nullable = false)
  private String senderId;

  @Column(nullable = false)
  private String preview;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected FeedEntryRow() {}

  public FeedEntryRow(
      String id,
      String userId,
      String channelId,
      String messageId,
      String senderId,
      String preview,
      Instant createdAt) {
    this.id = id;
    this.userId = userId;
    this.channelId = channelId;
    this.messageId = messageId;
    this.senderId = senderId;
    this.preview = preview;
    this.createdAt = createdAt;
  }

  public Entities.FeedEntry toDomain() {
    return new Entities.FeedEntry(id, userId, channelId, messageId, senderId, preview, createdAt);
  }

  public String getId() {
    return id;
  }

  public String getUserId() {
    return userId;
  }

  public String getChannelId() {
    return channelId;
  }

  public String getMessageId() {
    return messageId;
  }

  public String getSenderId() {
    return senderId;
  }

  public String getPreview() {
    return preview;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
