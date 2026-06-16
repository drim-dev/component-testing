package dev.drim.relay.store;

import dev.drim.relay.store.entity.NotificationRow;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<NotificationRow, String> {
  @Query(
      """
      SELECT n FROM NotificationRow n
      WHERE n.userId = :userId AND (:before IS NULL OR n.id < :before)
      ORDER BY n.id DESC
      """)
  List<NotificationRow> page(
      @Param("userId") String userId, @Param("before") String before, Limit limit);
}
