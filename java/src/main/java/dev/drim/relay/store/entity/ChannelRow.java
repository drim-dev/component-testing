package dev.drim.relay.store.entity;

import dev.drim.relay.domain.Entities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "channels")
public class ChannelRow {
  @Id private String id;

  @Column(nullable = false)
  private String name;

  @Column(name = "private", nullable = false)
  private boolean isPrivate;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ChannelRow() {}

  public ChannelRow(String id, String name, boolean isPrivate, Instant createdAt) {
    this.id = id;
    this.name = name;
    this.isPrivate = isPrivate;
    this.createdAt = createdAt;
  }

  public Entities.Channel toDomain() {
    return new Entities.Channel(id, name, isPrivate, createdAt);
  }

  public String getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public boolean isPrivate() {
    return isPrivate;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
