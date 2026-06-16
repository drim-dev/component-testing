package dev.drim.relay.store.entity;

import dev.drim.relay.domain.Entities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "notifications")
public class NotificationRow {
  @Id private String id;

  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(name = "dm_message_id", nullable = false, unique = true)
  private String dmMessageId;

  @Column(name = "conversation_id", nullable = false)
  private String conversationId;

  @Column(name = "sender_id", nullable = false)
  private String senderId;

  @Column(nullable = false)
  private String preview;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected NotificationRow() {}

  public NotificationRow(
      String id,
      String userId,
      String dmMessageId,
      String conversationId,
      String senderId,
      String preview,
      Instant createdAt) {
    this.id = id;
    this.userId = userId;
    this.dmMessageId = dmMessageId;
    this.conversationId = conversationId;
    this.senderId = senderId;
    this.preview = preview;
    this.createdAt = createdAt;
  }

  public Entities.Notification toDomain() {
    return new Entities.Notification(
        id, userId, dmMessageId, conversationId, senderId, preview, createdAt);
  }

  public String getId() {
    return id;
  }

  public String getUserId() {
    return userId;
  }

  public String getDmMessageId() {
    return dmMessageId;
  }

  public String getConversationId() {
    return conversationId;
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
