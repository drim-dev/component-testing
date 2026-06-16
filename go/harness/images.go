// Package harness is Relay's test harness: the DependencyHarness interface (no I prefix —
// Go idiom) and the eight concrete harnesses (DatabaseHarness, RedisHarness, KafkaHarness,
// RabbitMqHarness, S3Harness, LlmHarness, UnfurlHarness, PresenceHarness). Each answers the
// same five questions — Start, wire the seam, Seed, Assert, Reset — in its own shape. Real
// deps run via testcontainers-go (digest-pinned, the SAME digests the .NET pilot recorded);
// the LLM is a hand-rolled in-process fake; outbound HTTP is a real local stub server.
//
// Harness code is reusable bricks only — no test-case logic.
package harness

// Container images, pinned by DIGEST (04-dependencies.md §0.3). These are the exact digests
// the .NET pilot recorded on 2026-06-11; all languages use them, so the companion is a frozen
// snapshot with no registry-tag drift.
const (
	PostgresImage = "postgres:17-alpine@sha256:979c4379dd698aba0b890599a6104e082035f98ef31d9b9291ec22f2b13059ca"
	RedisImage    = "redis:7-alpine@sha256:6ab0b6e7381779332f97b8ca76193e45b0756f38d4c0dcda72dbb3c32061ab99"
	KafkaImage    = "apache/kafka:3.9.0@sha256:fbc7d7c428e3755cf36518d4976596002477e4c052d1f80b5b9eafd06d0fff2f"
	RabbitMqImage = "rabbitmq:4-management-alpine@sha256:96827325bdd90cb6feecd35bd9e37276876359a092570550edc58ce234273c15"
	MinioImage    = "minio/minio:latest@sha256:14cea493d9a34af32f524e538b8346cf79f3321eff8e708c1e2960462bd8936e"
)
