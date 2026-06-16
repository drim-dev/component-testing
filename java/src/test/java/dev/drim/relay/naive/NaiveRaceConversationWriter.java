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
 * The G-RACE naive variant (exhibit, NEVER in src/): check-then-insert with NO unique-conflict
 * handling. A small test-only delay between the existence check and the insert widens the TOCTOU
 * window deterministically (05-gallery.md §0.4 point 5) — it changes the timing, not the shape of
 * the bug (still a missing unique-conflict handler). Under concurrent creates this produces either
 * duplicate rows or a 500. Caught by DirectMessagesTest S-DM-05 + GRaceNaiveDemoTest.
 */
public final class NaiveRaceConversationWriter implements ConversationWriter {
  private final ConversationRepository conversations;
  private final DmParticipantRepository participants;
  private final IdFactory ids;

  public NaiveRaceConversationWriter(
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
    // Test-only widening of the TOCTOU window — the bug is the missing unique-conflict recovery,
    // not the delay (the correct writer is timing-independent and needs no such hook).
    try {
      Thread.sleep(40);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    ConversationRow row = new ConversationRow(ids.create(), userLo, userHi, Instant.now());
    conversations.save(row);
    participants.save(new DmParticipantRow(row.getId(), userLo));
    participants.save(new DmParticipantRow(row.getId(), userHi));
    return new ConversationCreateResult(row.toDomain(), true);
  }
}
