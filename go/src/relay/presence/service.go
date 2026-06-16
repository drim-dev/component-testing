// Package presence holds the companion-owned gRPC presence service (the real transport the
// G-GRPC catch exercises) and the API-side client seam. Presence lives in Redis under
// presence:{userId} with a 60 s TTL set by the heartbeat; the unary RPC reads one key, the
// streaming RPC emits exactly one status per requested user then closes cleanly.
package presence

import (
	"context"
	"sync/atomic"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/presence/presencepb"
	"github.com/redis/go-redis/v9"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// KeyPrefix is the Redis key namespace for presence; the heartbeat writes the same key.
const KeyPrefix = "presence:"

// StreamFault is test-only fault control (04-dependencies.md §8): armed to "fail after N",
// the streaming RPC writes N statuses then aborts mid-stream with a gRPC error — the
// deterministic partial-stream probe. Unset (the production default) → the stream always
// completes cleanly; the service code path is identical either way.
type StreamFault struct {
	failAfter atomic.Int32 // 0 = disarmed (sentinel); armed value is N+1
}

// FailAfter arms the fault: the next stream emits messages statuses then aborts.
func (f *StreamFault) FailAfter(messages int) { f.failAfter.Store(int32(messages) + 1) }

// Clear disarms the fault.
func (f *StreamFault) Clear() { f.failAfter.Store(0) }

func (f *StreamFault) limit() (int, bool) {
	v := f.failAfter.Load()
	if v == 0 {
		return 0, false
	}
	return int(v - 1), true
}

// Service implements the presence gRPC server over Redis.
type Service struct {
	presencepb.UnimplementedPresenceServer
	redis *redis.Client
	fault *StreamFault
}

// NewService builds the presence service backed by the given Redis client and fault flag.
func NewService(client *redis.Client, fault *StreamFault) *Service {
	return &Service{redis: client, fault: fault}
}

func (s *Service) GetPresence(ctx context.Context, req *presencepb.GetPresenceRequest) (*presencepb.PresenceStatus, error) {
	online, err := s.online(ctx, req.GetUserId())
	if err != nil {
		return nil, err
	}
	return &presencepb.PresenceStatus{UserId: req.GetUserId(), Online: online}, nil
}

func (s *Service) StreamChannelPresence(req *presencepb.StreamChannelPresenceRequest, stream presencepb.Presence_StreamChannelPresenceServer) error {
	limit, armed := s.fault.limit()
	for i, userID := range req.GetUserIds() {
		if armed && i >= limit {
			return status.Error(codes.Unavailable, "presence stream fault (test-only): aborting mid-stream")
		}
		online, err := s.online(stream.Context(), userID)
		if err != nil {
			return err
		}
		if err := stream.Send(&presencepb.PresenceStatus{UserId: userID, Online: online}); err != nil {
			return err
		}
	}
	return nil
}

func (s *Service) online(ctx context.Context, userID string) (bool, error) {
	n, err := s.redis.Exists(ctx, KeyPrefix+userID).Result()
	if err != nil {
		return false, err
	}
	return n > 0, nil
}
