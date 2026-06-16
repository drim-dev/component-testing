package dev.drim.relay.store;

import dev.drim.relay.store.entity.ChannelRow;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChannelRepository extends JpaRepository<ChannelRow, String> {
  /** Public channels plus the caller's channels, newest-first, keyset-paginated by id. */
  @Query(
      """
      SELECT DISTINCT c FROM ChannelRow c
      LEFT JOIN ChannelMemberRow m ON m.channelId = c.id AND m.userId = :userId
      WHERE (c.isPrivate = false OR m.userId IS NOT NULL)
        AND (:before IS NULL OR c.id < :before)
      ORDER BY c.id DESC
      """)
  List<ChannelRow> pageVisible(
      @Param("userId") String userId, @Param("before") String before, Limit limit);
}
