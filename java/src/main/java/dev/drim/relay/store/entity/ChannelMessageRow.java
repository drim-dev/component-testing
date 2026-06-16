package dev.drim.relay.store.entity;

import dev.drim.relay.domain.Entities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "channel_messages")
public class ChannelMessageRow {
  @Id private String id;

  @Column(name = "channel_id", nullable = false)
  private String channelId;

  @Column(name = "sender_id", nullable = false)
  private String senderId;

  @Column(nullable = false)
  private String text;

  @Column(name = "link_preview_title")
  private String linkPreviewTitle;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected ChannelMessageRow() {}

  public ChannelMessageRow(
      String id,
      String channelId,
      String senderId,
      String text,
      String linkPreviewTitle,
      Instant createdAt) {
    this.id = id;
    this.channelId = channelId;
    this.senderId = senderId;
    this.text = text;
    this.linkPreviewTitle = linkPreviewTitle;
    this.createdAt = createdAt;
  }

  public Entities.ChannelMessage toDomain() {
    return new Entities.ChannelMessage(id, channelId, senderId, text, linkPreviewTitle, createdAt);
  }

  public String getId() {
    return id;
  }

  public String getChannelId() {
    return channelId;
  }

  public String getSenderId() {
    return senderId;
  }

  public String getText() {
    return text;
  }

  public String getLinkPreviewTitle() {
    return linkPreviewTitle;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
