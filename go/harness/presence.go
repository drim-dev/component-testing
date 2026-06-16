package harness

import (
	"context"
	"fmt"
	"net"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/presence"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/presence/presencepb"
	"github.com/redis/go-redis/v9"
	"google.golang.org/grpc"
)

// PresenceHarness boots the REAL companion-owned presence gRPC service on an ephemeral
// 127.0.0.1 port over a real socket, so the API consumes it through genuine gRPC (cleartext
// h2c) — the transport-agnostic proof (not an in-process double). It shares the suite's Redis
// so a heartbeat is observable through the stream. Seed = set presence keys directly; fault
// control = arm the stream to fail after N (the deterministic partial-stream probe);
// Reset = clear the fault flag (presence keys are cleared by the suite's Redis FLUSHDB).
type PresenceHarness struct {
	redisAddr string
	redis     *redis.Client
	fault     *presence.StreamFault
	server    *grpc.Server
	listener  net.Listener
	addr      string
}

// NewPresenceHarness builds the harness against the suite's Redis address (known only after
// the Redis harness starts — an honest dependency between harnesses).
func NewPresenceHarness(redisAddr string) *PresenceHarness {
	return &PresenceHarness{redisAddr: redisAddr, fault: &presence.StreamFault{}}
}

func (h *PresenceHarness) Addr() string { return h.addr }

func (h *PresenceHarness) Start(_ context.Context) error {
	h.redis = redis.NewClient(&redis.Options{Addr: h.redisAddr})
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return fmt.Errorf("presence listen: %w", err)
	}
	h.listener = listener
	h.addr = listener.Addr().String()

	h.server = grpc.NewServer()
	presencepb.RegisterPresenceServer(h.server, presence.NewService(h.redis, h.fault))
	go func() { _ = h.server.Serve(listener) }()
	return nil
}

func (h *PresenceHarness) Reset(_ context.Context) error {
	h.fault.Clear()
	return nil
}

func (h *PresenceHarness) Stop(_ context.Context) error {
	if h.server != nil {
		h.server.GracefulStop()
	}
	if h.redis != nil {
		_ = h.redis.Close()
	}
	return nil
}

// SetOnline marks a user online directly (the same key the heartbeat writes), 60 s TTL.
func (h *PresenceHarness) SetOnline(ctx context.Context, userID string) error {
	return h.redis.Set(ctx, presence.KeyPrefix+userID, "1", 60*time.Second).Err()
}

// FailStreamAfter arms the partial-stream fault: the next stream emits n statuses then aborts.
func (h *PresenceHarness) FailStreamAfter(n int) { h.fault.FailAfter(n) }

var _ DependencyHarness = (*PresenceHarness)(nil)
