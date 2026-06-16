// Package relaytest is the Relay component-test suite: it covers the 06-acceptance catalog
// 1:1 (one test per scenario, the scenario id embedded in the test name for mechanical
// conformance grep) through the REAL HTTP boundary against REAL dependencies, plus the
// paired lying/naive exhibits from 05-gallery. One shared Fixture (one Docker host, one
// suite); each test resets all dependencies first.
package relaytest

import (
	"context"
	"fmt"
	"os"
	"testing"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/harness"
)

// fixture is the suite-wide composition, started once in TestMain.
var fixture *harness.Fixture

func TestMain(m *testing.M) {
	os.Exit(run(m))
}

func run(m *testing.M) int {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	fixture = harness.NewFixture()
	if err := fixture.Start(ctx); err != nil {
		fmt.Fprintln(os.Stderr, "fixture start failed:", err)
		return 1
	}
	defer func() {
		stopCtx, stopCancel := context.WithTimeout(context.Background(), 60*time.Second)
		defer stopCancel()
		_ = fixture.Stop(stopCtx)
	}()

	return m.Run()
}

// reset returns the suite to a clean state. Called at the start of every test (the suite is
// shared and serial — never run two Testcontainers suites on one Docker host).
func reset(t *testing.T) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	if err := fixture.Reset(ctx); err != nil {
		t.Fatalf("reset: %v", err)
	}
}
