package dev.drim.relay.store.entity;

import dev.drim.relay.domain.Entities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class UserRow {
  @Id private String id;

  @Column(nullable = false, unique = true)
  private String handle;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected UserRow() {}

  public UserRow(String id, String handle, String displayName, Instant createdAt) {
    this.id = id;
    this.handle = handle;
    this.displayName = displayName;
    this.createdAt = createdAt;
  }

  public Entities.User toDomain() {
    return new Entities.User(id, handle, displayName, createdAt);
  }

  public String getId() {
    return id;
  }

  public String getHandle() {
    return handle;
  }

  public String getDisplayName() {
    return displayName;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
