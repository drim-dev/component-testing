package dev.drim.relay.naive;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.seams.ChannelReadGate;
import dev.drim.relay.store.ChannelRepository;
import dev.drim.relay.web.ApiException;

/**
 * The G-BOLA-READ naive variant (exhibit, NEVER in src/): the read path checks only that the
 * channel EXISTS and never consults {@code private} for the caller — so a non-member reads a
 * private channel's metadata and messages (200 instead of the existence-hiding 404). Correct rule,
 * missing wiring. Caught by ChannelsTest S-CH-05/21 + GBolaReadNaiveDemoTest.
 */
public final class NaiveChannelReadGate implements ChannelReadGate {
  private final ChannelRepository channels;

  public NaiveChannelReadGate(ChannelRepository channels) {
    this.channels = channels;
  }

  @Override
  public Entities.Channel authorizeRead(String channelId, String userId, boolean isMessages) {
    return channels
        .findById(channelId)
        .map(r -> r.toDomain())
        .orElseThrow(() -> ApiException.notFound("channel:not_found", "Channel not found."));
  }
}
