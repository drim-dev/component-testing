package harness

import "context"

// DependencyHarness is the central abstraction (04-dependencies.md §0): every dependency's
// harness answers the same lifecycle. Go names it without an I prefix (locked). The
// per-dependency Seed / Assert / fault-control methods live on the concrete structs (their
// shapes differ), so this interface is just the uniform lifecycle the composition drives.
//
// Extensibility here = add a harness struct, not runtime re-composition (honest framing).
type DependencyHarness interface {
	// Start brings up the real dependency (Testcontainers) or constructs the fake, and
	// records the connection config the app under test will use.
	Start(ctx context.Context) error
	// Reset returns the dependency to a clean state between tests, fast.
	Reset(ctx context.Context) error
	// Stop tears the dependency down at suite end.
	Stop(ctx context.Context) error
}
