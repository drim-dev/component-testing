// Package workers holds the background broker consumers: the Kafka feed-fanout consumer and
// the RabbitMQ notification worker. Both take a seam (FeedProjector / NotificationRecorder)
// so a consumer-side naive variant can be injected through the same constructor seam as the
// rest. Offsets/acks are applied only AFTER the seam's effect is persisted, so the harness's
// await-idle assertion ("group lag == 0" / "queue settled") implies the effects are durable.
package workers

import (
	"context"
	"log/slog"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/infra"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/seams"
	"github.com/twmb/franz-go/pkg/kgo"
)

// FeedConsumer runs the feed-fanout consumer group. Delivery is at-least-once and the
// projector is idempotent (G-KAFKA consumer); the offset is committed only after Apply
// succeeds, so a processing failure is retried (never silently skipped).
type FeedConsumer struct {
	client    *kgo.Client
	projector seams.FeedProjector
	log       *slog.Logger
}

func NewFeedConsumer(client *kgo.Client, projector seams.FeedProjector, log *slog.Logger) *FeedConsumer {
	return &FeedConsumer{client: client, projector: projector, log: log}
}

// Run consumes until ctx is cancelled. Manual commits: the offset advances only after the
// projector persisted the event's effects.
func (c *FeedConsumer) Run(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}
		fetches := c.client.PollFetches(ctx)
		if ctx.Err() != nil {
			return
		}
		if errs := fetches.Errors(); len(errs) > 0 {
			// Broker unavailable or transient fetch error — franz-go self-heals; keep polling.
			continue
		}
		failed := false
		fetches.EachRecord(func(record *kgo.Record) {
			if failed {
				return
			}
			ev, err := infra.DeserializeMessagePosted(record.Value)
			if err != nil {
				c.log.Debug("skip undecodable event", "err", err)
				return
			}
			if err := c.projector.Apply(ctx, ev); err != nil {
				// Processing failed before commit: do not advance the offset; the record is
				// re-fetched and retried. The projector's idempotency makes the retry safe.
				c.log.Debug("feed fanout failed; will retry", "err", err)
				failed = true
			}
		})
		if failed {
			continue // do not commit; the uncommitted records are re-polled
		}
		if err := c.client.CommitUncommittedOffsets(ctx); err != nil {
			c.log.Debug("commit failed; will retry", "err", err)
		}
	}
}
