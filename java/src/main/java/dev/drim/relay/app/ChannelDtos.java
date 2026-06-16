package dev.drim.relay.app;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.drim.relay.domain.Entities;
import java.time.Instant;
import java.util.List;

/** The channel / membership / channel-message wire shapes (02-api.md §3). */
public final class ChannelDtos {
  private ChannelDtos() {}

  /**
   * memberCount is omitted on create (matches the Go {@code omitempty}): a freshly created channel
   * reports no count, while list/get carry it. Jackson drops the null field.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ChannelDto(
      String id,
      String name,
      @JsonProperty("private") boolean isPrivate,
      Integer memberCount,
      Instant createdAt) {

    public static ChannelDto created(Entities.Channel c) {
      return new ChannelDto(c.id(), c.name(), c.isPrivate(), null, c.createdAt());
    }

    public static ChannelDto withCount(Entities.Channel c, long count) {
      return new ChannelDto(c.id(), c.name(), c.isPrivate(), (int) count, c.createdAt());
    }
  }

  public record MembershipDto(String channelId, String userId, String role, Instant joinedAt) {}

  public record ChannelMessageDto(
      String id,
      String channelId,
      String senderId,
      String text,
      List<String> attachmentIds,
      String linkPreviewTitle,
      Instant createdAt) {

    public static ChannelMessageDto of(Entities.ChannelMessage m, List<String> attachmentIds) {
      return new ChannelMessageDto(
          m.id(),
          m.channelId(),
          m.senderId(),
          m.text(),
          attachmentIds == null ? List.of() : List.copyOf(attachmentIds),
          m.linkPreviewTitle(),
          m.createdAt());
    }
  }
}
