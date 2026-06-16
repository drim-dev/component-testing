package dev.drim.relay.app;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.seams.ChannelReadGate;
import dev.drim.relay.store.ChannelMemberRepository;
import dev.drim.relay.store.ChannelRepository;
import dev.drim.relay.web.ApiException;
import org.springframework.stereotype.Component;

/**
 * The correct G-BOLA-READ seam: the 404/403 visibility split. Private + non-member → 404 (existence
 * hidden, byte-identical to unknown id). Public + non-member → 200 metadata but 403 for messages.
 * The naive variant never consults the private flag for the caller.
 */
@Component
public class CorrectChannelReadGate implements ChannelReadGate {
  private final ChannelRepository channels;
  private final ChannelMemberRepository members;

  public CorrectChannelReadGate(ChannelRepository channels, ChannelMemberRepository members) {
    this.channels = channels;
    this.members = members;
  }

  @Override
  public Entities.Channel authorizeRead(String channelId, String userId, boolean isMessages) {
    var channel =
        channels.findById(channelId).map(r -> r.toDomain()).orElseThrow(NotFounds::channel);
    boolean member = members.findByChannelIdAndUserId(channelId, userId).isPresent();
    if (member) {
      return channel;
    }
    if (channel.isPrivate()) {
      throw NotFounds.channel();
    }
    if (isMessages) {
      throw ApiException.forbidden(
          "channel:membership_required", "Membership is required to read messages.");
    }
    return channel;
  }
}
