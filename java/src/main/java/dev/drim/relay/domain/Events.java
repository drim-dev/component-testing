package dev.drim.relay.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/** The cross-dependency message shapes (Kafka event, RabbitMQ job, presence, LLM request). */
public final class Events {
  private Events() {}

  /**
   * The Kafka event (topic message-posted, key = channelId) fanned out to members' feeds + unread
   * counters. Field names are the JSON wire contract shared across all five languages.
   */
  public record MessagePosted(
      @JsonProperty("messageId") String messageId,
      @JsonProperty("channelId") String channelId,
      @JsonProperty("senderId") String senderId,
      @JsonProperty("preview") String preview,
      @JsonProperty("postedAt") Instant postedAt) {}

  /**
   * The RabbitMQ job (queue notify.dm) the worker turns into a notification row, exactly once per
   * DM message under at-least-once redelivery.
   */
  public record NotificationJob(
      @JsonProperty("dmMessageId") String dmMessageId,
      @JsonProperty("conversationId") String conversationId,
      @JsonProperty("senderId") String senderId,
      @JsonProperty("recipientId") String recipientId,
      @JsonProperty("preview") String preview) {}

  /** One member's presence (from the gRPC stream / unary RPC). */
  public record PresenceStatus(String userId, boolean online) {}

  /**
   * What the app hands the SummaryModel: a constant system prompt plus the messages as
   * already-rendered, delimited DATA blocks. The fake verifies the system prompt equals the pinned
   * constant and that hostile text appears ONLY inside a block (G-LLM).
   */
  public record SummaryRequest(String systemPrompt, List<String> messageBlocks) {}
}
