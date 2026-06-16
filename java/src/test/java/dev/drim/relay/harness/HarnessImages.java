package dev.drim.relay.harness;

/**
 * Container images, pinned by DIGEST (04-dependencies.md §0.3). These are the EXACT digests the
 * .NET pilot recorded on 2026-06-11 (go/harness/images.go); all five languages use them, so the
 * companion is a frozen snapshot with no registry-tag drift.
 */
public final class HarnessImages {
  public static final String POSTGRES =
      "postgres:17-alpine@sha256:979c4379dd698aba0b890599a6104e082035f98ef31d9b9291ec22f2b13059ca";
  public static final String REDIS =
      "redis:7-alpine@sha256:6ab0b6e7381779332f97b8ca76193e45b0756f38d4c0dcda72dbb3c32061ab99";
  public static final String KAFKA =
      "apache/kafka:3.9.0@sha256:fbc7d7c428e3755cf36518d4976596002477e4c052d1f80b5b9eafd06d0fff2f";
  public static final String RABBITMQ =
      "rabbitmq:4-management-alpine@sha256:96827325bdd90cb6feecd35bd9e37276876359a092570550edc58ce234273c15";
  public static final String MINIO =
      "minio/minio:latest@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e";

  private HarnessImages() {}
}
