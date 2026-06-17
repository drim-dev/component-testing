package harness

import (
	"context"
	"sync"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/presence/presencepb"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// presenceStub is a canned-response stand-in for the neighbour presence service. It answers
// the unary and streaming RPCs from an in-memory online set, with a test-only fault that
// aborts the stream after N messages (the deterministic partial-stream probe for G-GRPC).
// No Redis, no neighbour dependencies — just the contract the Relay API consumes.
type presenceStub struct {
	presencepb.UnimplementedPresenceServer
	mu        sync.Mutex
	online    map[string]struct{}
	failAfter int // 0 = disarmed (sentinel); armed value is N+1
}

func newPresenceStub() *presenceStub {
	return &presenceStub{online: map[string]struct{}{}}
}

func (s *presenceStub) setOnline(userID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.online[userID] = struct{}{}
}

func (s *presenceStub) failStreamAfter(n int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.failAfter = n + 1
}

func (s *presenceStub) reset() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.online = map[string]struct{}{}
	s.failAfter = 0
}

func (s *presenceStub) isOnline(userID string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, ok := s.online[userID]
	return ok
}

func (s *presenceStub) limit() (int, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.failAfter == 0 {
		return 0, false
	}
	return s.failAfter - 1, true
}

func (s *presenceStub) GetPresence(_ context.Context, req *presencepb.GetPresenceRequest) (*presencepb.PresenceStatus, error) {
	return &presencepb.PresenceStatus{UserId: req.GetUserId(), Online: s.isOnline(req.GetUserId())}, nil
}

func (s *presenceStub) StreamChannelPresence(req *presencepb.StreamChannelPresenceRequest, stream presencepb.Presence_StreamChannelPresenceServer) error {
	limit, armed := s.limit()
	for i, userID := range req.GetUserIds() {
		if armed && i >= limit {
			return status.Error(codes.Unavailable, "presence stream fault (test-only): aborting mid-stream")
		}
		if err := stream.Send(&presencepb.PresenceStatus{UserId: userID, Online: s.isOnline(userID)}); err != nil {
			return err
		}
	}
	return nil
}
