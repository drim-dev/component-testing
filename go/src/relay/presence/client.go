package presence

import (
	"context"
	"errors"
	"io"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/presence/presencepb"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/seams"
	"google.golang.org/grpc"
)

// Client is the correct G-GRPC seam: it consumes the server stream to its CLEAN end-of-stream
// (io.EOF) and reports a mid-stream transport error as Incomplete — so the handler returns
// 502 and NEVER a partial member list as complete. The naive variant swallows the error and
// returns whatever arrived.
type Client struct {
	grpc presencepb.PresenceClient
}

// NewClient builds the presence client seam over an existing gRPC connection.
func NewClient(conn grpc.ClientConnInterface) seams.PresenceClient {
	return &Client{grpc: presencepb.NewPresenceClient(conn)}
}

func (c *Client) UserPresence(ctx context.Context, userID string) (bool, error) {
	status, err := c.grpc.GetPresence(ctx, &presencepb.GetPresenceRequest{UserId: userID})
	if err != nil {
		return false, err
	}
	return status.GetOnline(), nil
}

func (c *Client) ChannelPresence(ctx context.Context, userIDs []string) (seams.PresenceResult, error) {
	stream, err := c.grpc.StreamChannelPresence(ctx, &presencepb.StreamChannelPresenceRequest{UserIds: userIDs})
	if err != nil {
		return seams.PresenceResult{}, err
	}
	var statuses []domain.PresenceStatus
	for {
		msg, err := stream.Recv()
		if errors.Is(err, io.EOF) {
			// Clean end-of-stream: the full set arrived.
			return seams.PresenceResult{Statuses: statuses}, nil
		}
		if err != nil {
			// A mid-stream abort means we did NOT reach clean end-of-stream: the list we hold
			// is partial. Surfacing it as complete is the gallery bug — report it incomplete.
			return seams.PresenceResult{Incomplete: true}, nil
		}
		statuses = append(statuses, domain.PresenceStatus{UserID: msg.GetUserId(), Online: msg.GetOnline()})
	}
}
