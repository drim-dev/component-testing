package relaytest

import (
	"fmt"
	"testing"
)

// expectCatchToFail is the expect-failure wrapper (05-gallery §0.4, gate item) — the Go
// idiom. It runs the catching test's OWN assertion block against an app where the gallery
// case's correct seam has been replaced by its naive variant, and asserts those assertions
// FAIL — i.e. the catching test goes red against the bug. This keeps a RED demonstration
// executable inside a GREEN suite.
//
// In Go there is no exception to catch; the assertion block takes a testing.TB and we run it
// against a RECORDING TB. If the block records a failure (Errorf/Fatalf/FailNow), the naive
// variant was caught → the demo passes. If the block records NOTHING, the catching test does
// not actually catch this gallery case (a false proof) → the wrapper fails the real test.
//
// Implemented with the harness's NaiveApp seam swap (no DI framework — one struct-field swap).
func expectCatchToFail(t *testing.T, galleryCaseID string, catchingAssertions func(t testing.TB)) {
	t.Helper()
	rec := &recordingTB{}
	func() {
		defer func() {
			// A FailNow on the recording TB calls runtime.Goexit via t.FailNow only on a real
			// *testing.T; our recorder records and the block may also panic on a nil deref it
			// triggered against the buggy app — both count as "caught".
			if r := recover(); r != nil {
				rec.failed = true
			}
		}()
		catchingAssertions(rec)
	}()
	if !rec.failed {
		t.Fatalf("naive-variant demonstration for %s did NOT go red: the catching assertions "+
			"passed against the naive (buggy) implementation. The catching test is not actually "+
			"catching this gallery case.", galleryCaseID)
	}
}

// recordingTB is a minimal testing.TB that records whether a failure was reported, instead of
// failing the surrounding test. Only the methods our assertion blocks use are meaningful;
// the rest satisfy the interface.
type recordingTB struct {
	testing.TB
	failed bool
}

func (r *recordingTB) Errorf(format string, args ...any) { r.failed = true }
func (r *recordingTB) Error(args ...any)                 { r.failed = true }
func (r *recordingTB) Fatalf(format string, args ...any) { r.failed = true; panic(failNow{}) }
func (r *recordingTB) Fatal(args ...any)                 { r.failed = true; panic(failNow{}) }
func (r *recordingTB) FailNow()                          { r.failed = true; panic(failNow{}) }
func (r *recordingTB) Fail()                             { r.failed = true }
func (r *recordingTB) Helper()                           {}
func (r *recordingTB) Logf(format string, args ...any)   {}
func (r *recordingTB) Log(args ...any)                   {}
func (r *recordingTB) Name() string                      { return "naive-demo" }

type failNow struct{}

var _ = fmt.Sprintf
