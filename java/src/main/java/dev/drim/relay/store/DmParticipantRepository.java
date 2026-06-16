package dev.drim.relay.store;

import dev.drim.relay.store.entity.DmParticipantRow;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DmParticipantRepository
    extends JpaRepository<DmParticipantRow, DmParticipantRow.Key> {
  boolean existsByConversationIdAndUserId(String conversationId, String userId);
}
