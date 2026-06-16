package dev.drim.relay.store.entity;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.domain.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "channel_members")
@IdClass(ChannelMemberRow.Key.class)
public class ChannelMemberRow {
  @Id
  @Column(name = "channel_id", nullable = false)
  private String channelId;

  @Id
  @Column(name = "user_id", nullable = false)
  private String userId;

  @Column(nullable = false)
  private String role;

  @Column(name = "joined_at", nullable = false)
  private Instant joinedAt;

  protected ChannelMemberRow() {}

  public ChannelMemberRow(String channelId, String userId, Role role, Instant joinedAt) {
    this.channelId = channelId;
    this.userId = userId;
    this.role = role.wire();
    this.joinedAt = joinedAt;
  }

  public Entities.ChannelMember toDomain() {
    return new Entities.ChannelMember(channelId, userId, Role.parse(role), joinedAt);
  }

  public String getChannelId() {
    return channelId;
  }

  public String getUserId() {
    return userId;
  }

  public String getRole() {
    return role;
  }

  public void setRole(Role role) {
    this.role = role.wire();
  }

  public Instant getJoinedAt() {
    return joinedAt;
  }

  public static class Key implements Serializable {
    private String channelId;
    private String userId;

    public Key() {}

    public Key(String channelId, String userId) {
      this.channelId = channelId;
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
      return Objects.equals(channelId, key.channelId) && Objects.equals(userId, key.userId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(channelId, userId);
    }
  }
}
