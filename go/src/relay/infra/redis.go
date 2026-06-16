// Package infra holds the concrete infrastructure-backed implementations of the app's ports:
// Redis (membership cache, unread counters, heartbeats), the HTTP link previewer with its
// Redis-backed circuit breaker, the Kafka publisher, the RabbitMQ job publisher, and the S3
// object store. These are the "correct" wiring the composition root assembles into Deps.
package infra

import (
	"context"
	"strconv"
	"strings"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/presence"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/seams"
	"github.com/redis/go-redis/v9"
)

const (
	membershipTTL = 300 * time.Second
	presenceTTL   = 60 * time.Second
)

// MembershipCache is the Redis membership fast-path (members:{channelId}, a set) coupled to
// invalidation on membership writes. Its coherence with Postgres is the G-CACHE property.
type MembershipCache struct{ client *redis.Client }

func NewMembershipCache(client *redis.Client) *MembershipCache {
	return &MembershipCache{client: client}
}

func membersKey(channelID string) string { return "members:" + channelID }

func (c *MembershipCache) IsMember(ctx context.Context, channelID, userID string) (cached, member bool, err error) {
	key := membersKey(channelID)
	exists, err := c.client.Exists(ctx, key).Result()
	if err != nil {
		return false, false, err
	}
	if exists == 0 {
		return false, false, nil
	}
	isMember, err := c.client.SIsMember(ctx, key, userID).Result()
	if err != nil {
		return false, false, err
	}
	return true, isMember, nil
}

func (c *MembershipCache) Remember(ctx context.Context, channelID string, memberIDs []string) error {
	key := membersKey(channelID)
	pipe := c.client.TxPipeline()
	pipe.Del(ctx, key)
	if len(memberIDs) > 0 {
		members := make([]any, len(memberIDs))
		for i, id := range memberIDs {
			members[i] = id
		}
		pipe.SAdd(ctx, key, members...)
		pipe.Expire(ctx, key, membershipTTL)
	}
	_, err := pipe.Exec(ctx)
	return err
}

func (c *MembershipCache) Invalidate(ctx context.Context, channelID string) error {
	return c.client.Del(ctx, membersKey(channelID)).Err()
}

// UnreadCounters is the Redis per-channel unread counter (unread:{userId}:{channelId}).
type UnreadCounters struct{ client *redis.Client }

func NewUnreadCounters(client *redis.Client) *UnreadCounters { return &UnreadCounters{client: client} }

func unreadKey(userID, channelID string) string { return "unread:" + userID + ":" + channelID }

func (u *UnreadCounters) Increment(ctx context.Context, userID, channelID string) error {
	return u.client.Incr(ctx, unreadKey(userID, channelID)).Err()
}

func (u *UnreadCounters) Reset(ctx context.Context, userID, channelID string) error {
	return u.client.Del(ctx, unreadKey(userID, channelID)).Err()
}

func (u *UnreadCounters) ForUser(ctx context.Context, userID string) (map[string]int64, error) {
	prefix := "unread:" + userID + ":"
	out := map[string]int64{}
	var cursor uint64
	for {
		keys, next, err := u.client.Scan(ctx, cursor, prefix+"*", 100).Result()
		if err != nil {
			return nil, err
		}
		for _, key := range keys {
			channelID := strings.TrimPrefix(key, prefix)
			raw, err := u.client.Get(ctx, key).Result()
			if err != nil {
				return nil, err
			}
			n, _ := strconv.ParseInt(raw, 10, 64)
			out[channelID] = n
		}
		cursor = next
		if cursor == 0 {
			break
		}
	}
	return out, nil
}

// Heartbeats writes the presence key the gRPC service reads (presence:{userId}, 60 s TTL).
type Heartbeats struct{ client *redis.Client }

func NewHeartbeats(client *redis.Client) *Heartbeats { return &Heartbeats{client: client} }

func (h *Heartbeats) Mark(ctx context.Context, userID string) error {
	return h.client.Set(ctx, presence.KeyPrefix+userID, "1", presenceTTL).Err()
}

var (
	_ seams.MembershipCache = (*MembershipCache)(nil)
	_ seams.UnreadCounters  = (*UnreadCounters)(nil)
	_ seams.Heartbeats      = (*Heartbeats)(nil)
)
