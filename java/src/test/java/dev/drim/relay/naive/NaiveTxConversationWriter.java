package dev.drim.relay.naive;

import dev.drim.relay.id.IdFactory;
import dev.drim.relay.seams.ConversationWriter;
import dev.drim.relay.seams.Seams.ConversationCreateResult;
import dev.drim.relay.store.ConversationRepository;
import dev.drim.relay.store.DmParticipantRepository;
import dev.drim.relay.store.entity.ConversationRow;
import dev.drim.relay.store.entity.DmParticipantRow;
import java.time.Instant;

/**
 * The G-TX naive variant (exhibit, NEVER in src/): three separate saves with NO transaction. When
 * the harness's armed trigger raises on the SECOND participant insert, the conversation row and the
 * first participant row are already committed — an orphaned partial write. The correct writer wraps
 * all three in one transaction so they roll back together. Caught by DirectMessagesTest S-DM-06 +
 * GTxNaiveDemoTest.
 */
public final class NaiveTxConversationWriter implements ConversationWriter {
  private final ConversationRepository conversations;
  private final DmParticipantRepository participants;
  private final IdFactory ids;

  public NaiveTxConversationWriter(
      ConversationRepository conversations, DmParticipantRepository participants, IdFactory ids) {
    this.conversations = conversations;
    this.participants = participants;
    this.ids = ids;
  }

  @Override
  public ConversationCreateResult create(String userLo, String userHi) {
    var existing = conversations.findByUserLoAndUserHi(userLo, userHi);
    if (existing.isPresent()) {
      return new ConversationCreateResult(existing.get().toDomain(), false);
    }
    ConversationRow row = new ConversationRow(ids.create(), userLo, userHi, Instant.now());
    // The bug: each save commits on its own (no @Transactional boundary). A failure on the second
    // participant insert leaves the conversation + first participant behind.
    conversations.saveAndFlush(row);
    participants.saveAndFlush(new DmParticipantRow(row.getId(), userLo));
    participants.saveAndFlush(new DmParticipantRow(row.getId(), userHi));
    return new ConversationCreateResult(row.toDomain(), true);
  }
}
