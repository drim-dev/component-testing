// DependencyHarness is the central abstraction (../../spec/04-dependencies.md
// §0): every dependency's harness answers the same lifecycle. The
// per-dependency seed / assert / fault-control methods live on the concrete
// classes (their shapes differ), so this interface is just the uniform lifecycle
// the composition drives. TS/Java/Python use the same concrete names as .NET/Go:
// DatabaseHarness, RedisHarness, KafkaHarness, RabbitMqHarness, S3Harness,
// LlmHarness, UnfurlHarness, PresenceHarness.
//
// Extensibility here = add a harness class, not runtime re-composition (honest
// framing — design §2.3).

export interface DependencyHarness {
  // start brings up the real dependency (testcontainers) or constructs the fake,
  // and records the connection config the app under test will use.
  start(): Promise<void>;
  // reset returns the dependency to a clean state between tests, fast.
  reset(): Promise<void>;
  // stop tears the dependency down at suite end.
  stop(): Promise<void>;
}
