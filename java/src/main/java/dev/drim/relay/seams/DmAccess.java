package dev.drim.relay.seams;

import dev.drim.relay.domain.Entities;
import java.util.Optional;

/**
 * The G-IDOR seam: participant-scoped conversation read. Returns empty when the caller is not a
 * participant (or the conversation is absent), and the route 404s — hiding existence. The naive
 * variant loads by id only.
 */
public interface DmAccess {
  Optional<Entities.Conversation> getForParticipant(String conversationId, String userId);
}
