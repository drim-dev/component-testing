package dev.drim.relay.seams;

import dev.drim.relay.seams.Seams.ConversationCreateResult;

/**
 * The G-RACE / G-TX seam: a transactional, unique-conflict-handling create. Concurrent creates for
 * one pair resolve to a single row (RACE); a mid-write failure leaves nothing behind (TX). The
 * naive variants are check-then-insert (RACE) and three saves with no transaction (TX).
 */
public interface ConversationWriter {
  ConversationCreateResult create(String userLo, String userHi);
}
