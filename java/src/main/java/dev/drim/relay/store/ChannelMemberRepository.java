package dev.drim.relay.store;

import dev.drim.relay.store.entity.ChannelMemberRow;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ChannelMemberRepository
    extends JpaRepository<ChannelMemberRow, ChannelMemberRow.Key> {
  Optional<ChannelMemberRow> findByChannelIdAndUserId(String channelId, String userId);

  long countByChannelId(String channelId);

  // A bulk DELETE issues one statement in its own transaction — a derived deleteBy would load the
  // entity and call EntityManager.remove(), which requires an ambient transaction the membership
  // writer does not open (it crosses no @Transactional proxy).
  @Modifying
  @Transactional
  @Query("DELETE FROM ChannelMemberRow m WHERE m.channelId = :channelId AND m.userId = :userId")
  void deleteByChannelIdAndUserId(
      @Param("channelId") String channelId, @Param("userId") String userId);

  @Query("SELECT m.userId FROM ChannelMemberRow m WHERE m.channelId = :channelId")
  List<String> memberIds(@Param("channelId") String channelId);

  @Query(
      "SELECT m.userId FROM ChannelMemberRow m WHERE m.channelId = :channelId AND m.userId <> :except")
  List<String> memberIdsExcept(
      @Param("channelId") String channelId, @Param("except") String except);
}
