package dev.drim.relay.store;

import dev.drim.relay.store.entity.FeedEntryRow;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FeedEntryRepository extends JpaRepository<FeedEntryRow, String> {
  @Query(
      """
      SELECT f FROM FeedEntryRow f
      WHERE f.userId = :userId AND (:before IS NULL OR f.id < :before)
      ORDER BY f.id DESC
      """)
  List<FeedEntryRow> page(
      @Param("userId") String userId, @Param("before") String before, Limit limit);
}
