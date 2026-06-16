package dev.drim.relay.seams;

/**
 * Marks a user online (TTL 60 s) by writing the SAME Redis key the presence gRPC service reads, so
 * a heartbeat is observable through both presence paths.
 */
public interface Heartbeats {
  void mark(String userId);
}
