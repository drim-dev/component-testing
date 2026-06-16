package dev.drim.relay.app;

import dev.drim.relay.domain.Events;
import dev.drim.relay.id.IdFactory;
import dev.drim.relay.seams.FeedProjector;
import dev.drim.relay.seams.UnreadCounters;
import dev.drim.relay.store.ChannelMemberRepository;
import dev.drim.relay.store.FeedEntryRepository;
import dev.drim.relay.store.entity.FeedEntryRow;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * The correct G-KAFKA consumer seam: idempotent per (user, message). The UNIQUE (user_id,
 * message_id) constraint is the backstop, and the unread counter is incremented ONLY on a first
 * successful insert — so feed and counter never diverge under redelivery. The naive variant inserts
 * and increments unconditionally.
 */
@Component
public class CorrectFeedProjector implements FeedProjector {
  private final ChannelMemberRepository members;
  private final FeedEntryRepository feed;
  private final UnreadCounters unread;
  private final IdFactory ids;

  public CorrectFeedProjector(
      ChannelMemberRepository members,
      FeedEntryRepository feed,
      UnreadCounters unread,
      IdFactory ids) {
    this.members = members;
    this.feed = feed;
    this.unread = unread;
    this.ids = ids;
  }

  @Override
  public void apply(Events.MessagePosted event) {
    for (String memberId : members.memberIdsExcept(event.channelId(), event.senderId())) {
      FeedEntryRow row =
          new FeedEntryRow(
              ids.create(),
              memberId,
              event.channelId(),
              event.messageId(),
              event.senderId(),
              event.preview(),
              event.postedAt());
      try {
        feed.saveAndFlush(row);
      } catch (DataIntegrityViolationException e) {
        continue; // already projected — do NOT increment again
      }
      unread.increment(memberId, event.channelId());
    }
  }
}
