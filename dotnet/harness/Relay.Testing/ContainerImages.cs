namespace Relay.Testing;

/// <summary>
/// Container images, pinned in ONE place so every harness and every language uses the
/// same versions (spec/04-dependencies.md §0.3).
/// </summary>
/// <remarks>
/// LOCKED decision: images are pinned BY DIGEST (<c>image:tag@sha256:…</c>) to remove
/// registry-tag drift — the one freeze-compatible breakage vector. The tag stays in the
/// reference for human readability; the digest is what Docker resolves. Digests recorded
/// 2026-06-11 by the .NET pilot; all languages use these exact digests
/// (spec/04-dependencies.md §0.3 carries the same table).
/// </remarks>
public static class ContainerImages
{
    public const string PostgreSql =
        "postgres:17-alpine@sha256:979c4379dd698aba0b890599a6104e082035f98ef31d9b9291ec22f2b13059ca";

    public const string Redis =
        "redis:7-alpine@sha256:6ab0b6e7381779332f97b8ca76193e45b0756f38d4c0dcda72dbb3c32061ab99";

    public const string Kafka =
        "apache/kafka:3.9.0@sha256:fbc7d7c428e3755cf36518d4976596002477e4c052d1f80b5b9eafd06d0fff2f";

    public const string RabbitMq =
        "rabbitmq:4-management-alpine@sha256:96827325bdd90cb6feecd35bd9e37276876359a092570550edc58ce234273c15";

    public const string Minio =
        "minio/minio:latest@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e";
}
