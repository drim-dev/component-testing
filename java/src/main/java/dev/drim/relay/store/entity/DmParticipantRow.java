package dev.drim.relay.store.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "dm_participants")
@IdClass(DmParticipantRow.Key.class)
public class DmParticipantRow {
  @Id
  @Column(name = "conversation_id", nullable = false)
  private String conversationId;

  @Id
  @Column(name = "user_id", nullable = false)
  private String userId;

  protected DmParticipantRow() {}

  public DmParticipantRow(String conversationId, String userId) {
    this.conversationId = conversationId;
    this.userId = userId;
  }

  public String getConversationId() {
    return conversationId;
  }

  public String getUserId() {
    return userId;
  }

  public static class Key implements Serializable {
    private String conversationId;
    private String userId;

    public Key() {}

    public Key(String conversationId, String userId) {
      this.conversationId = conversationId;
      this.userId = userId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Key key)) {
        return false;
      }
      return Objects.equals(conversationId, key.conversationId)
          && Objects.equals(userId, key.userId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(conversationId, userId);
    }
  }
}
