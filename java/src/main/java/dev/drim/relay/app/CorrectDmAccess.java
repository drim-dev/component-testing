package dev.drim.relay.app;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.seams.DmAccess;
import dev.drim.relay.store.ConversationRepository;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The correct G-IDOR seam: load the conversation, then APPLY the participant predicate. A
 * non-participant gets empty → the route 404s, hiding existence. The naive variant skips the check.
 */
@Component
public class CorrectDmAccess implements DmAccess {
  private final ConversationRepository conversations;

  public CorrectDmAccess(ConversationRepository conversations) {
    this.conversations = conversations;
  }

  @Override
  public Optional<Entities.Conversation> getForParticipant(String conversationId, String userId) {
    return conversations
        .findById(conversationId)
        .map(row -> row.toDomain())
        .filter(c -> c.isParticipant(userId));
  }
}
