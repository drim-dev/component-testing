package dev.drim.relay.naive;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.domain.Role;
import dev.drim.relay.seams.ChannelRoleGate;
import dev.drim.relay.store.ChannelMemberRepository;
import dev.drim.relay.store.ChannelRepository;
import dev.drim.relay.web.ApiException;

/**
 * The G-BOLA-ROLE naive variant (exhibit, NEVER in src/): it checks that the caller is a MEMBER but
 * skips the role compare — so a plain member performs an admin-only action (add/kick/delete) and
 * gets 200/204 instead of 403. {@code role.atLeast(minRole)} exists; it is simply not called here.
 * Caught by ChannelsTest S-CH-11/15/19 + GBolaRoleNaiveDemoTest.
 */
public final class NaiveChannelRoleGate implements ChannelRoleGate {
  private final ChannelRepository channels;
  private final ChannelMemberRepository members;

  public NaiveChannelRoleGate(ChannelRepository channels, ChannelMemberRepository members) {
    this.channels = channels;
    this.members = members;
  }

  @Override
  public Entities.ChannelMember authorizeRole(String channelId, String userId, Role minRole) {
    var channel =
        channels
            .findById(channelId)
            .map(r -> r.toDomain())
            .orElseThrow(() -> ApiException.notFound("channel:not_found", "Channel not found."));
    var member =
        members.findByChannelIdAndUserId(channelId, userId).map(r -> r.toDomain()).orElse(null);
    if (member == null) {
      if (channel.isPrivate()) {
        throw ApiException.notFound("channel:not_found", "Channel not found.");
      }
      throw ApiException.forbidden("channel:membership_required", "Membership is required.");
    }
    // The bug: the role is never compared — any member passes every gate.
    return member;
  }
}
