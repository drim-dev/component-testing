package dev.drim.relay.store;

import dev.drim.relay.store.entity.ChannelMessageRow;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChannelMessageRepository extends JpaRepository<ChannelMessageRow, String> {
  boolean existsByChannelIdAndId(String channelId, String id);

  @Query(
      """
      SELECT m FROM ChannelMessageRow m
      WHERE m.channelId = :channelId AND (:before IS NULL OR m.id < :before)
      ORDER BY m.id DESC
      """)
  List<ChannelMessageRow> page(
      @Param("channelId") String channelId, @Param("before") String before, Limit limit);

  /** Newest N messages for the AI summary (oldest-first ordering applied by the caller). */
  @Query("SELECT m FROM ChannelMessageRow m WHERE m.channelId = :channelId ORDER BY m.id DESC")
  List<ChannelMessageRow> newest(@Param("channelId") String channelId, Limit limit);
}
