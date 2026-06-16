package dev.drim.relay.naive;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.seams.MembershipWriter;
import dev.drim.relay.store.ChannelMemberRepository;
import dev.drim.relay.store.entity.ChannelMemberRow;

/**
 * The G-CACHE naive variant (exhibit, NEVER in src/): it writes Postgres and FORGETS to invalidate
 * the Redis membership cache — so a removed member's next read is still authorized by the stale
 * cache. Caught by ChannelsTest S-CH-16 / FeedTest S-FD-06 + GCacheNaiveDemoTest.
 */
public final class NaiveMembershipWriter implements MembershipWriter {
  private final ChannelMemberRepository members;

  public NaiveMembershipWriter(ChannelMemberRepository members) {
    this.members = members;
  }

  @Override
  public void add(Entities.ChannelMember member) {
    members.save(
        new ChannelMemberRow(
            member.channelId(), member.userId(), member.role(), member.joinedAt()));
    // The bug: no cache.invalidate(channelId).
  }

  @Override
  public void remove(String channelId, String userId) {
    members.deleteByChannelIdAndUserId(channelId, userId);
    // The bug: no cache.invalidate(channelId) — the stale set still lists the removed member.
  }
}
