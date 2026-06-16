package dev.drim.relay.store;

import dev.drim.relay.store.entity.ConversationRow;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<ConversationRow, String> {
  Optional<ConversationRow> findByUserLoAndUserHi(String userLo, String userHi);

  /**
   * The caller's conversations newest-first, keyset-paginated by id. A null {@code before} returns
   * the first page; fetch limit+1 so the caller can detect a next page.
   */
  @Query(
      """
      SELECT c FROM ConversationRow c
      JOIN DmParticipantRow p ON p.conversationId = c.id AND p.userId = :userId
      WHERE (:before IS NULL OR c.id < :before)
      ORDER BY c.id DESC
      """)
  List<ConversationRow> pageForUser(
      @Param("userId") String userId, @Param("before") String before, Limit limit);
}
