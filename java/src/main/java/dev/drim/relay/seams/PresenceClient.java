package dev.drim.relay.seams;

import dev.drim.relay.seams.Seams.PresenceResult;
import java.util.List;

/**
 * The G-GRPC seam: consume the presence stream to clean end; a mid-stream error sets {@code
 * incomplete} (→ 502, never a partial list as complete). The naive variant swallows the error and
 * returns what arrived.
 */
public interface PresenceClient {
  boolean userPresence(String userId);

  PresenceResult channelPresence(List<String> userIds);
}
