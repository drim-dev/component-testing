package dev.drim.relay.domain;

import java.time.Instant;

/**
 * Relay's entities and the pure predicates the gallery's honesty notes call out as the legitimate
 * home of unit tests (participant check, role ordering). Whether a route WIRES these in is a system
 * property the component tests verify; that the predicates are correct is unit territory.
 *
 * <p>These are the in-memory domain shapes the handlers and seams pass around. The JPA-mapped
 * persistence rows live in {@code dev.drim.relay.store.entity}; repositories translate.
 */
public final class Entities {
  private Entities() {}

  /** A Relay account; handle is unique. */
  public record User(String id, String handle, String displayName, Instant createdAt) {}

  /** A 1:1 DM, the pair stored normalized (userLo &lt; userHi). */
  public record Conversation(String id, String userLo, String userHi, Instant createdAt) {
    /**
     * The DM access predicate — pure logic, the G-IDOR honesty note's unit target. A read path that
     * never calls it is the bug, not this method.
     */
    public boolean isParticipant(String userId) {
      return userLo.equals(userId) || userHi.equals(userId);
    }
  }

  /** One message in a conversation. */
  public record DmMessage(
      String id, String conversationId, String senderId, String text, Instant createdAt) {}

  /** A community space. */
  public record Channel(String id, String name, boolean isPrivate, Instant createdAt) {}

  /** A (channel, user) membership with a role. */
  public record ChannelMember(String channelId, String userId, Role role, Instant joinedAt) {}

  /** A message in a channel; linkPreviewTitle is null unless an unfurl ran. */
  public record ChannelMessage(
      String id,
      String channelId,
      String senderId,
      String text,
      String linkPreviewTitle,
      Instant createdAt) {}

  /**
   * The metadata row for a stored file; access derives from channel membership, NEVER from
   * possession of storageKey (G-S3). messageId is null until referenced by a message create.
   */
  public record Attachment(
      String id,
      String channelId,
      String uploaderId,
      String messageId,
      String filename,
      long sizeBytes,
      String storageKey,
      Instant createdAt) {}

  /**
   * A recipient's record of one DM message; dmMessageId is the unique idempotency anchor
   * (G-RABBIT).
   */
  public record Notification(
      String id,
      String userId,
      String dmMessageId,
      String conversationId,
      String senderId,
      String preview,
      Instant createdAt) {}

  /** A channel-fanout projection row; (userId, messageId) is unique (G-KAFKA). */
  public record FeedEntry(
      String id,
      String userId,
      String channelId,
      String messageId,
      String senderId,
      String preview,
      Instant createdAt) {}

  /** Returns the two ids in lexicographic order (lo, hi). */
  public static String[] normalizePair(String a, String b) {
    return a.compareTo(b) < 0 ? new String[] {a, b} : new String[] {b, a};
  }
}
