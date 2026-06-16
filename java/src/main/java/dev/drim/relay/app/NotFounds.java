package dev.drim.relay.app;

import dev.drim.relay.web.ApiException;

/**
 * The existence-hiding 404s (01-domain.md §5): each returns the SAME code+message for an unknown id
 * and for an unauthorized caller, so their JSON bodies are byte-identical — exactly what the G-IDOR
 * / G-BOLA-READ / G-S3 catches assert.
 */
public final class NotFounds {
  private NotFounds() {}

  public static ApiException conversation() {
    return ApiException.notFound("dm:conversation:not_found", "Conversation not found.");
  }

  public static ApiException channel() {
    return ApiException.notFound("channel:not_found", "Channel not found.");
  }

  public static ApiException attachment() {
    return ApiException.notFound("attachment:not_found", "Attachment not found.");
  }
}
