package dev.drim.relay.store;

import dev.drim.relay.store.entity.DmMessageRow;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DmMessageRepository extends JpaRepository<DmMessageRow, String> {
  boolean existsByConversationIdAndId(String conversationId, String id);

  @Query(
      """
      SELECT m FROM DmMessageRow m
      WHERE m.conversationId = :conversationId AND (:before IS NULL OR m.id < :before)
      ORDER BY m.id DESC
      """)
  List<DmMessageRow> page(
      @Param("conversationId") String conversationId, @Param("before") String before, Limit limit);
}
