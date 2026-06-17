// Package presence holds the companion-owned gRPC presence service (the neighbour's own
// production code) and the API-side client seam. Presence lives in Redis under
// presence:{userId} with a 60 s TTL set by the heartbeat; the unary RPC reads one key, the
// streaming RPC emits exactly one status per requested user then closes cleanly. In a
// component test of the Relay API this neighbour is stubbed (see harness), not run — this is
// the real implementation it stands in for.
package presence

import (
	"context"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/presence/presencepb"
	"github.com/redis/go-redis/v9"
)

// KeyPrefix is the Redis key namespace for presence; the heartbeat writes the same key.
const KeyPrefix = "presence:"

// Service implements the presence gRPC server over Redis.
type Service struct {
	presencepb.UnimplementedPresenceServer
	redis *redis.Client
}

// NewService builds the presence service backed by the given Redis client.
func NewService(client *redis.Client) *Service {
	return &Service{redis: client}
}

func (s *Service) GetPresence(ctx context.Context, req *presencepb.GetPresenceRequest) (*presencepb.PresenceStatus, error) {
	online, err := s.online(ctx, req.GetUserId())
	if err != nil {
		return nil, err
	}
	return &presencepb.PresenceStatus{UserId: req.GetUserId(), Online: online}, nil
}

func (s *Service) StreamChannelPresence(req *presencepb.StreamChannelPresenceRequest, stream presencepb.Presence_StreamChannelPresenceServer) error {
	for _, userID := range req.GetUserIds() {
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
