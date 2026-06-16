package dev.drim.relay.seams;

/**
 * The object-store port (S3). Bytes live behind an opaque storage key; authorization NEVER reads
 * key possession (G-S3) — that is enforced via {@link AttachmentAccess}.
 */
public interface AttachmentStore {
  void put(String key, byte[] data);

  byte[] get(String key);

  void deleteAll();
}
