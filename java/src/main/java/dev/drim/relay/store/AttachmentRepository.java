package dev.drim.relay.store;

import dev.drim.relay.store.entity.AttachmentRow;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AttachmentRepository extends JpaRepository<AttachmentRow, String> {
  /**
   * Ids among {@code ids} that the uploader owns in this channel — for message-create attachment
   * validation (the S3 case's authorization derives from channel membership, never key possession).
   */
  @Query(
      """
      SELECT a.id FROM AttachmentRow a
      WHERE a.channelId = :channelId AND a.uploaderId = :uploaderId AND a.id IN :ids
      """)
  List<String> ownedInChannel(
      @Param("channelId") String channelId,
      @Param("uploaderId") String uploaderId,
      @Param("ids") List<String> ids);

  @Modifying
  @Query("UPDATE AttachmentRow a SET a.messageId = :messageId WHERE a.id IN :ids")
  void bindToMessage(@Param("messageId") String messageId, @Param("ids") List<String> ids);
}
