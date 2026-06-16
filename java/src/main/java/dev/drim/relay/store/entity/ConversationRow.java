package dev.drim.relay.store.entity;

import dev.drim.relay.domain.Entities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "dm_conversations")
public class ConversationRow {
  @Id private String id;

  @Column(name = "user_lo", nullable = false)
  private String userLo;

  @Column(name = "user_hi", nullable = false)
  private String userHi;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ConversationRow() {}

  public ConversationRow(String id, String userLo, String userHi, Instant createdAt) {
    this.id = id;
    this.userLo = userLo;
    this.userHi = userHi;
    this.createdAt = createdAt;
  }

  public Entities.Conversation toDomain() {
    return new Entities.Conversation(id, userLo, userHi, createdAt);
  }

  public String getId() {
    return id;
  }

  public String getUserLo() {
    return userLo;
  }

  public String getUserHi() {
    return userHi;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
