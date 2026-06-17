package harness

import (
	"context"
	"fmt"
	"net"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/presence/presencepb"
	"google.golang.org/grpc"
)

// PresenceHarness boots a STUB presence gRPC server on an ephemeral 127.0.0.1 port over a real
// socket, so the API still consumes presence through genuine gRPC (cleartext h2c) — the
// transport-agnostic proof — without dragging the neighbour's own dependencies (its Redis)
// into the test. Presence is a NEIGHBOUR service, so in a component test of the Relay API it
// is stubbed, not run for real. SetOnline = program the canned answer; FailStreamAfter = arm
// the partial-stream fault (the deterministic probe); Reset = clear the online set and fault.
type PresenceHarness struct {
	stub     *presenceStub
	server   *grpc.Server
	listener net.Listener
	addr     string
}

// NewPresenceHarness builds the presence harness; it owns nothing but a loopback port.
func NewPresenceHarness() *PresenceHarness {
	return &PresenceHarness{stub: newPresenceStub()}
}

func (h *PresenceHarness) Addr() string { return h.addr }

func (h *PresenceHarness) Start(_ context.Context) error {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return fmt.Errorf("presence listen: %w", err)
	}
	h.listener = listener
	h.addr = listener.Addr().String()

	h.server = grpc.NewServer()
	presencepb.RegisterPresenceServer(h.server, h.stub)
	go func() { _ = h.server.Serve(listener) }()
	return nil
}

func (h *PresenceHarness) Reset(_ context.Context) error {
	h.stub.reset()
	return nil
}

func (h *PresenceHarness) Stop(_ context.Context) error {
	if h.server != nil {
		h.server.GracefulStop()
	}
	return nil
}

// SetOnline programs the stub: mark a user online in its canned answer.
func (h *PresenceHarness) SetOnline(userID string) { h.stub.setOnline(userID) }

// FailStreamAfter arms the partial-stream fault: the next stream emits n statuses then aborts.
func (h *PresenceHarness) FailStreamAfter(n int) { h.stub.failStreamAfter(n) }

var _ DependencyHarness = (*PresenceHarness)(nil)
