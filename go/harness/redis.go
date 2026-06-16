package harness

import (
	"context"
	"fmt"
	"time"

	"github.com/redis/go-redis/v9"
	"github.com/testcontainers/testcontainers-go"
	tcredis "github.com/testcontainers/testcontainers-go/modules/redis"
	"github.com/testcontainers/testcontainers-go/wait"
)

// RedisHarness is the Redis harness (cache / counters / breaker state). Real container.
// Seed = set keys directly (e.g. pre-warm a membership cache to prove invalidation);
// Assert = read keys/TTLs; Reset = FLUSHDB (the trivially fast reset — contrast with PG).
type RedisHarness struct {
	container *tcredis.RedisContainer
	addr      string
	client    *redis.Client
}

func (h *RedisHarness) Addr() string          { return h.addr }
func (h *RedisHarness) Client() *redis.Client { return h.client }

func (h *RedisHarness) Start(ctx context.Context) error {
	container, err := tcredis.Run(ctx, RedisImage,
		testcontainers.WithWaitStrategy(wait.ForLog("Ready to accept connections").WithStartupTimeout(60*time.Second)),
	)
	if err != nil {
		return fmt.Errorf("start redis: %w", err)
	}
	h.container = container
	uri, err := container.ConnectionString(ctx)
	if err != nil {
		return fmt.Errorf("redis uri: %w", err)
	}
	opt, err := redis.ParseURL(uri)
	if err != nil {
		return fmt.Errorf("parse redis url: %w", err)
	}
	h.addr = opt.Addr
	h.client = redis.NewClient(opt)
	return nil
}

func (h *RedisHarness) Reset(ctx context.Context) error {
	return h.client.FlushDB(ctx).Err()
}

func (h *RedisHarness) Stop(ctx context.Context) error {
	if h.client != nil {
		_ = h.client.Close()
	}
	if h.container != nil {
		return h.container.Terminate(ctx)
	}
	return nil
}

// SeedMembershipCache pre-warms members:{channelId} so a test can prove invalidation (G-CACHE).
func (h *RedisHarness) SeedMembershipCache(ctx context.Context, channelID string, memberIDs ...string) error {
	key := "members:" + channelID
	pipe := h.client.TxPipeline()
	pipe.Del(ctx, key)
	members := make([]any, len(memberIDs))
	for i, id := range memberIDs {
		members[i] = id
	}
	pipe.SAdd(ctx, key, members...)
	pipe.Expire(ctx, key, 300*time.Second)
	_, err := pipe.Exec(ctx)
	return err
}

// CacheHasMember reports whether the cached set for a channel exists and contains a user.
func (h *RedisHarness) CacheHasMember(ctx context.Context, channelID, userID string) (exists, member bool, err error) {
	key := "members:" + channelID
	n, err := h.client.Exists(ctx, key).Result()
	if err != nil {
		return false, false, err
	}
	if n == 0 {
		return false, false, nil
	}
	m, err := h.client.SIsMember(ctx, key, userID).Result()
	return true, m, err
}

var _ DependencyHarness = (*RedisHarness)(nil)
