package dev.drim.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.drim.relay.domain.Entities;
import dev.drim.relay.seams.DmAccess;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LYING TEST (exhibit, do not copy) — gallery case G-IDOR; caught by DirectMessagesTest
 * S-DM-08/09/10 + GIdorNaiveDemoTest.
 *
 * <p>This is §3 lift #2, the "stubbed authorization guard": it mocks the DmAccess seam to ALWAYS
 * return the conversation, then "verifies" the read path returns it. It is green by construction —
 * the security decision (is the caller a participant?) is switched OFF inside the test, so it
 * passes against the naive load-by-id variant just as happily as against the correct one. It proves
 * nothing about the assembled route. Kept as an exhibit, paired with its catcher.
 */
class GIdorLyingTest {

  @Test
  @DisplayName("G-IDOR LYING: stubbed DmAccess returns the conversation → green, proves nothing")
  void stubbedGuardAlwaysGreen() {
    DmAccess dmAccess = mock(DmAccess.class);
    Entities.Conversation conv =
        new Entities.Conversation("conv-1", "user-a", "user-b", Instant.now());
    // The lie: the guard is stubbed to authorize unconditionally — no participant check is
    // exercised.
    when(dmAccess.getForParticipant("conv-1", "intruder")).thenReturn(Optional.of(conv));

    Optional<Entities.Conversation> result = dmAccess.getForParticipant("conv-1", "intruder");

    assertThat(result).isPresent();
    assertThat(result.get().id()).isEqualTo("conv-1");
  }
}
