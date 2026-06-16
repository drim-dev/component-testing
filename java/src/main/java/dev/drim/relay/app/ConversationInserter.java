package dev.drim.relay.app;

import dev.drim.relay.store.ConversationRepository;
import dev.drim.relay.store.DmParticipantRepository;
import dev.drim.relay.store.entity.ConversationRow;
import dev.drim.relay.store.entity.DmParticipantRow;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The atomic conversation+participants write (03-schema.md: a multi-row atomic write is the TX
 * gallery case's surface). A separate bean so {@code @Transactional} crosses a Spring proxy
 * boundary — self-invocation from {@link CorrectConversationWriter} would silently bypass the
 * transaction. A mid-write failure (e.g. the TX catching test's injected trigger on the second
 * participant) rolls back conversation and both participants together: nothing left behind.
 */
@Component
public class ConversationInserter {
  private final ConversationRepository conversations;
  private final DmParticipantRepository participants;

  public ConversationInserter(
      ConversationRepository conversations, DmParticipantRepository participants) {
    this.conversations = conversations;
    this.participants = participants;
  }

  @Transactional
  public void insert(ConversationRow row) {
    conversations.saveAndFlush(row);
    participants.save(new DmParticipantRow(row.getId(), row.getUserLo()));
    participants.save(new DmParticipantRow(row.getId(), row.getUserHi()));
    participants.flush();
  }
}
