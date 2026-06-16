package harness

import (
	"context"
	"sync"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/seams"
)

// LlmHarness is the canonical FAKE (04-dependencies.md §6): nondeterministic, paid, external,
// so the boundary is a deliberate in-process double, not a container. Seed = program the next
// response (canned / empty / oversized); Assert = interaction verification — the captured
// request is where the prompt-injection catch lives; Reset = clear responses + captured calls.
// Hand-rolled on purpose (no mocking framework) so the pattern reads cross-language.
type LlmHarness struct {
	mu         sync.Mutex
	programmed []string
	captured   []domain.SummaryRequest
}

func (h *LlmHarness) Start(_ context.Context) error { return nil }
func (h *LlmHarness) Reset(_ context.Context) error { h.Clear(); return nil }
func (h *LlmHarness) Stop(_ context.Context) error  { return nil }

// Model returns the fake as the app's SummaryModel seam.
func (h *LlmHarness) Model() seams.SummaryModel { return &fakeSummaryModel{harness: h} }

// ProgramResponse seeds the next response (FIFO). Unprogrammed → a canned summary.
func (h *LlmHarness) ProgramResponse(response string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.programmed = append(h.programmed, response)
}

// CapturedRequests returns the requests the app made — for interaction verification.
func (h *LlmHarness) CapturedRequests() []domain.SummaryRequest {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := make([]domain.SummaryRequest, len(h.captured))
	copy(out, h.captured)
	return out
}

func (h *LlmHarness) Clear() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.programmed = nil
	h.captured = nil
}

type fakeSummaryModel struct{ harness *LlmHarness }

func (m *fakeSummaryModel) Complete(_ context.Context, req domain.SummaryRequest) (string, error) {
	m.harness.mu.Lock()
	defer m.harness.mu.Unlock()
	m.harness.captured = append(m.harness.captured, req)
	if len(m.harness.programmed) > 0 {
		response := m.harness.programmed[0]
		m.harness.programmed = m.harness.programmed[1:]
		return response, nil
	}
	return "(canned summary)", nil
}

var _ DependencyHarness = (*LlmHarness)(nil)
