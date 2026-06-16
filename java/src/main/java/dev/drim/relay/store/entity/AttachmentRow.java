package dev.drim.relay.store.entity;

import dev.drim.relay.domain.Entities;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "attachments")
public class AttachmentRow {
  @Id private String id;

  @Column(name = "channel_id", nullable = false)
  private String channelId;

  @Column(name = "uploader_id", nullable = false)
  private String uploaderId;

  @Column(name = "message_id")
  private String messageId;

  @Column(nullable = false)
  private String filename;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "storage_key", nullable = false, unique = true)
  private String storageKey;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AttachmentRow() {}

  public AttachmentRow(
      String id,
      String channelId,
      String uploaderId,
      String messageId,
      String filename,
      long sizeBytes,
      String storageKey,
      Instant createdAt) {
    this.id = id;
    this.channelId = channelId;
    this.uploaderId = uploaderId;
    this.messageId = messageId;
    this.filename = filename;
    this.sizeBytes = sizeBytes;
    this.storageKey = storageKey;
    this.createdAt = createdAt;
  }

  public Entities.Attachment toDomain() {
    return new Entities.Attachment(
        id, channelId, uploaderId, messageId, filename, sizeBytes, storageKey, createdAt);
  }

  public String getId() {
    return id;
  }

  public String getChannelId() {
    return channelId;
  }

  public String getUploaderId() {
    return uploaderId;
  }

  public String getMessageId() {
    return messageId;
  }

  public void setMessageId(String messageId) {
    this.messageId = messageId;
  }

  public String getFilename() {
    return filename;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
