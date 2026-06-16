package dev.drim.relay.app;

import dev.drim.relay.id.IdFactory;
import dev.drim.relay.seams.ConversationWriter;
import dev.drim.relay.seams.Seams.ConversationCreateResult;
import dev.drim.relay.store.ConversationRepository;
import dev.drim.relay.store.entity.ConversationRow;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * The correct G-RACE / G-TX seam: one transaction inserts the conversation + both participant rows
 * (via {@link ConversationInserter}), and a unique-pair violation (the concurrent loser) is
 * recovered by reading back the winner's row. Timing-independent — no test hook needed. The naive
 * variants are check-then-insert (RACE) and three saves with no transaction (TX).
 */
@Component
public class CorrectConversationWriter implements ConversationWriter {
  private final ConversationRepository conversations;
  private final ConversationInserter inserter;
  private final IdFactory ids;

  public CorrectConversationWriter(
      ConversationRepository conversations, ConversationInserter inserter, IdFactory ids) {
    this.conversations = conversations;
    this.inserter = inserter;
    this.ids = ids;
  }

  @Override
  public ConversationCreateResult create(String userLo, String userHi) {
    var existing = conversations.findByUserLoAndUserHi(userLo, userHi);
    if (existing.isPresent()) {
      return new ConversationCreateResult(existing.get().toDomain(), false);
    }

    ConversationRow row = new ConversationRow(ids.create(), userLo, userHi, Instant.now());
    try {
      inserter.insert(row);
      return new ConversationCreateResult(row.toDomain(), true);
    } catch (DataIntegrityViolationException e) {
      // A concurrent create won the unique-pair race; return its row (idempotent).
      return conversations
          .findByUserLoAndUserHi(userLo, userHi)
          .map(winner -> new ConversationCreateResult(winner.toDomain(), false))
          .orElseThrow(() -> e);
    }
  }
}
