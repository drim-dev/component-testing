package dev.drim.relay.store.entity;

import dev.drim.relay.domain.Entities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "dm_messages")
public class DmMessageRow {
  @Id private String id;

  @Column(name = "conversation_id", nullable = false)
  private String conversationId;

  @Column(name = "sender_id", nullable = false)
  private String senderId;

  @Column(nullable = false)
  private String text;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected DmMessageRow() {}

  public DmMessageRow(
      String id, String conversationId, String senderId, String text, Instant createdAt) {
    this.id = id;
    this.conversationId = conversationId;
    this.senderId = senderId;
    this.text = text;
    this.createdAt = createdAt;
  }

  public Entities.DmMessage toDomain() {
    return new Entities.DmMessage(id, conversationId, senderId, text, createdAt);
  }

  public String getId() {
    return id;
  }

  public String getConversationId() {
    return conversationId;
  }

  public String getSenderId() {
    return senderId;
  }

  public String getText() {
    return text;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
