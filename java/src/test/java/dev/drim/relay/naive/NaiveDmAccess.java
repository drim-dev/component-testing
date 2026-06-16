package dev.drim.relay.naive;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.seams.DmAccess;
import dev.drim.relay.store.ConversationRepository;
import java.util.Optional;

/**
 * The G-IDOR naive variant (exhibit, NEVER in src/): loads the conversation BY ID ONLY and skips
 * the participant predicate — the "correct logic, missing wiring" shape an agent ships (the
 * Tea/McHire bug). {@code isParticipant} exists and is correct; it is simply never called on this
 * route, so any authenticated user reads any conversation. Injected as the {@code @Primary}
 * DmAccess inside the G-IDOR naive-demo test, where the catching assertions go red against it.
 */
public final class NaiveDmAccess implements DmAccess {
  private final ConversationRepository conversations;

  public NaiveDmAccess(ConversationRepository conversations) {
    this.conversations = conversations;
  }

  @Override
  public Optional<Entities.Conversation> getForParticipant(String conversationId, String userId) {
    return conversations.findById(conversationId).map(row -> row.toDomain());
  }
}
