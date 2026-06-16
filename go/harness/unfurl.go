package harness

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"time"
)

// unfurlMode controls how the stub answers /unfurl.
type unfurlMode int

const (
	unfurlOK unfurlMode = iota
	unfurlDelay
	unfurl500
	unfurlReset
)

// UnfurlHarness is the outbound-HTTP harness: a REAL local stub server (not an in-process
// client mock — the timeout, the socket, and the status codes must be real). Seed = program
// the route (200+title / delay > timeout / 500 / connection reset); Assert = received-request
// count (circuit-breaker proof); Reset = clear route + counter.
type UnfurlHarness struct {
	server   *httptest.Server
	mu       sync.Mutex
	mode     unfurlMode
	title    string
	delay    time.Duration
	requests atomic.Int64
}

func (h *UnfurlHarness) BaseURL() string { return h.server.URL }

// RequestCount is the number of /unfurl requests the stub received (breaker proof).
func (h *UnfurlHarness) RequestCount() int64 { return h.requests.Load() }

func (h *UnfurlHarness) Start(_ context.Context) error {
	mux := http.NewServeMux()
	mux.HandleFunc("/unfurl", h.handle)
	h.server = httptest.NewServer(mux)
	h.mode = unfurlOK
	h.title = "Example"
	return nil
}

func (h *UnfurlHarness) Reset(_ context.Context) error {
	h.mu.Lock()
	h.mode = unfurlOK
	h.title = "Example"
	h.delay = 0
	h.mu.Unlock()
	h.requests.Store(0)
	return nil
}

func (h *UnfurlHarness) Stop(_ context.Context) error {
	if h.server != nil {
		h.server.Close()
	}
	return nil
}

// ProgramOK programs a 200 response with the given title.
func (h *UnfurlHarness) ProgramOK(title string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.mode = unfurlOK
	h.title = title
}

// ProgramDelay programs a response slower than the 800 ms client timeout.
func (h *UnfurlHarness) ProgramDelay(delay time.Duration) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.mode = unfurlDelay
	h.delay = delay
}

// ProgramServerError programs a 500 response.
func (h *UnfurlHarness) ProgramServerError() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.mode = unfurl500
}

func (h *UnfurlHarness) handle(w http.ResponseWriter, r *http.Request) {
	h.requests.Add(1)
	h.mu.Lock()
	mode, title, delay := h.mode, h.title, h.delay
	h.mu.Unlock()

	switch mode {
	case unfurlDelay:
		select {
		case <-time.After(delay):
		case <-r.Context().Done():
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"title": title})
	case unfurl500:
		w.WriteHeader(http.StatusInternalServerError)
	case unfurlReset:
		if hj, ok := w.(http.Hijacker); ok {
			conn, _, err := hj.Hijack()
			if err == nil {
				_ = conn.Close()
			}
		}
	default:
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]string{"title": title})
	}
}

var _ DependencyHarness = (*UnfurlHarness)(nil)
