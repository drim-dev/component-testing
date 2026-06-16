package dev.drim.relay.naive;

import dev.drim.relay.domain.Events;
import dev.drim.relay.id.IdFactory;
import dev.drim.relay.seams.FeedProjector;
import dev.drim.relay.seams.UnreadCounters;
import dev.drim.relay.store.ChannelMemberRepository;
import dev.drim.relay.store.FeedEntryRepository;
import dev.drim.relay.store.entity.FeedEntryRow;

/**
 * The G-KAFKA consumer naive variant (exhibit, NEVER in src/): inserts the feed entry and
 * increments the unread counter UNCONDITIONALLY, with no idempotency. Under at-least-once
 * redelivery a re-consumed event double-counts the unread counter (and would duplicate the feed
 * entry but for the DB UNIQUE constraint, which then makes feed and counter diverge). Caught by
 * FeedTest S-FD-05 + GKafkaConsumerNaiveDemoTest.
 */
public final class NaiveFeedProjector implements FeedProjector {
  private final ChannelMemberRepository members;
  private final FeedEntryRepository feed;
  private final UnreadCounters unread;
  private final IdFactory ids;

  public NaiveFeedProjector(
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
      // The bug: increment first, with no first-insert guard. On a redelivered event the counter
      // climbs again even though the feed entry already exists.
      unread.increment(memberId, event.channelId());
      try {
        feed.saveAndFlush(
            new FeedEntryRow(
                ids.create(),
                memberId,
                event.channelId(),
                event.messageId(),
                event.senderId(),
                event.preview(),
                event.postedAt()));
      } catch (org.springframework.dao.DataIntegrityViolationException ignored) {
        // Swallow the dup — feed stays at one row while the counter has already double-incremented:
        // feed and counter diverge, which is exactly the bug S-FD-05 catches.
      }
    }
  }
}
