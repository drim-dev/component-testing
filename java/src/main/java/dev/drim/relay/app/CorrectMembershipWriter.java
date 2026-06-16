package dev.drim.relay.app;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.seams.MembershipCache;
import dev.drim.relay.seams.MembershipWriter;
import dev.drim.relay.store.ChannelMemberRepository;
import dev.drim.relay.store.entity.ChannelMemberRow;
import org.springframework.stereotype.Component;

/**
 * The correct G-CACHE seam: a membership write (add/remove) coupled to invalidating the Redis
 * membership cache, so a removed member's next read is denied immediately. The naive variant writes
 * Postgres and forgets the invalidation, leaving a stale cache to authorize a removed member.
 */
@Component
public class CorrectMembershipWriter implements MembershipWriter {
  private final ChannelMemberRepository members;
  private final MembershipCache cache;

  public CorrectMembershipWriter(ChannelMemberRepository members, MembershipCache cache) {
    this.members = members;
    this.cache = cache;
  }

  @Override
  public void add(Entities.ChannelMember member) {
    members.save(
        new ChannelMemberRow(
            member.channelId(), member.userId(), member.role(), member.joinedAt()));
    cache.invalidate(member.channelId());
  }

  @Override
  public void remove(String channelId, String userId) {
    members.deleteByChannelIdAndUserId(channelId, userId);
    cache.invalidate(channelId);
  }
}
