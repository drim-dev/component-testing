package dev.drim.relay.app;

import dev.drim.relay.domain.Entities;
import java.time.Instant;
import java.util.List;

/** The DM wire shapes (02-api.md §2). */
public final class DmDtos {
  private DmDtos() {}

  public record ConversationDto(String id, List<String> participantIds, Instant createdAt) {
    public static ConversationDto of(Entities.Conversation c) {
      return new ConversationDto(c.id(), List.of(c.userLo(), c.userHi()), c.createdAt());
    }
  }

  public record DmMessageDto(
      String id, String conversationId, String senderId, String text, Instant createdAt) {
    public static DmMessageDto of(Entities.DmMessage m) {
      return new DmMessageDto(m.id(), m.conversationId(), m.senderId(), m.text(), m.createdAt());
    }
  }
}
