package dev.drim.relay.seams;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.domain.Events;
import java.util.List;

/**
 * The narrow interfaces each gallery case hides behind (05-gallery §0.4). The handlers depend on
 * these interfaces, never on concrete types. The CORRECT implementations are the {@code @Component}
 * beans in {@code dev.drim.relay.app}; the NAIVE variants live in {@code src/test} and are injected
 * through the SAME Spring seam — a {@code @TestConfiguration} {@code @Primary} bean overriding
 * exactly one of these. That a 404/403 decision is a property of the assembled application context
 * (not of a mock) is exactly what makes the catching tests catch and the lying tests lie.
 *
 * <p>The nine gallery seams: {@link DmAccess}, {@link ConversationWriter}, {@link ChannelReadGate},
 * {@link ChannelRoleGate}, {@link MembershipWriter}, {@link MessagePostedPublisher}, {@link
 * FeedProjector}, {@link NotificationRecorder}, {@link PresenceClient}. The remaining interfaces
 * are the dependency ports the harness swaps real↔fake.
 */
public final class Seams {
  private Seams() {}

  /** Carries the conversation and whether this call created it (201) or found one (200). */
  public record ConversationCreateResult(Entities.Conversation conversation, boolean created) {}

  /** The channel-presence outcome: per-member statuses, or incomplete when the stream errored. */
  public record PresenceResult(List<Events.PresenceStatus> statuses, boolean incomplete) {}

  /** One channel message handed to the summarizer (sender handle + text). */
  public record SummarySource(String handle, String text) {}
}
