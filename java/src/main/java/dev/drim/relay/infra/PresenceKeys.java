package dev.drim.relay.infra;

/**
 * The Redis key namespace for presence; the heartbeat writes the same key the gRPC service reads.
 */
public final class PresenceKeys {
  public static final String KEY_PREFIX = "presence:";

  private PresenceKeys() {}
}
