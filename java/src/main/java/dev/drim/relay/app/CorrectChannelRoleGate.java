package dev.drim.relay.app;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.domain.Role;
import dev.drim.relay.seams.ChannelRoleGate;
import dev.drim.relay.store.ChannelMemberRepository;
import dev.drim.relay.store.ChannelRepository;
import dev.drim.relay.web.ApiException;
import org.springframework.stereotype.Component;

/**
 * The correct G-BOLA-ROLE seam: membership AND the role compare. A plain member attempting an admin
 * action gets 403 (visible-but-forbidden); a non-member gets 404 (private) / 403 (public). The
 * naive variant checks membership but skips the role.
 */
@Component
public class CorrectChannelRoleGate implements ChannelRoleGate {
  private final ChannelRepository channels;
  private final ChannelMemberRepository members;

  public CorrectChannelRoleGate(ChannelRepository channels, ChannelMemberRepository members) {
    this.channels = channels;
    this.members = members;
  }

  @Override
  public Entities.ChannelMember authorizeRole(String channelId, String userId, Role minRole) {
    var channel =
        channels.findById(channelId).map(r -> r.toDomain()).orElseThrow(NotFounds::channel);
    var member =
        members.findByChannelIdAndUserId(channelId, userId).map(r -> r.toDomain()).orElse(null);
    if (member == null) {
      if (channel.isPrivate()) {
        throw NotFounds.channel();
      }
      throw ApiException.forbidden("channel:membership_required", "Membership is required.");
    }
    if (!member.role().atLeast(minRole)) {
      throw ApiException.forbidden(
          "channel:role:forbidden", "Your role does not permit this action.");
    }
    return member;
  }
}
