package infra

import (
	"context"
	"encoding/json"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/apierr"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/seams"
	"github.com/twmb/franz-go/pkg/kgo"
)

// MessagePostedTopic is the Kafka topic for message.posted events; key = channelId.
const MessagePostedTopic = "message-posted"

// publishConfirmTimeout bounds how long a post waits for broker confirmation. A reachable
// broker acks in milliseconds; a down broker must surface as 503 promptly and deterministically
// (the pinned broker-down behavior + the zero-flake gate), never hang on the producer's retries.
const publishConfirmTimeout = 3 * time.Second

// SerializeMessagePosted encodes the event payload — a pure function (unit territory).
func SerializeMessagePosted(ev domain.MessagePosted) []byte {
	b, _ := json.Marshal(ev)
	return b
}

// DeserializeMessagePosted decodes an event payload.
func DeserializeMessagePosted(b []byte) (domain.MessagePosted, error) {
	var ev domain.MessagePosted
	err := json.Unmarshal(b, &ev)
	return ev, err
}

// KafkaPublisher is the correct G-KAFKA producer seam: it AWAITS broker confirmation. If the
// broker is unavailable the synchronous produce returns an error, the caller rolls back the
// message transaction, and the API answers 503 — never fire-and-forget.
type KafkaPublisher struct {
	client *kgo.Client
	topic  string
}

func NewKafkaPublisher(client *kgo.Client, topic string) *KafkaPublisher {
	return &KafkaPublisher{client: client, topic: topic}
}

func (p *KafkaPublisher) Publish(ctx context.Context, ev domain.MessagePosted) error {
	record := &kgo.Record{Topic: p.topic, Key: []byte(ev.ChannelID), Value: SerializeMessagePosted(ev)}
	confirmCtx, cancel := context.WithTimeout(ctx, publishConfirmTimeout)
	defer cancel()

	// Await the broker ack — the confirmation the pinned write ordering requires — but never
	// past the bounded confirm context. franz-go's RecordDeliveryTimeout still reaps the
	// in-flight record on a down broker; selecting on confirmCtx guarantees the post path
	// itself surfaces the pinned 503 promptly and deterministically (zero-flake gate) rather
	// than blocking on producer-buffer/connection nuance.
	done := make(chan error, 1)
	p.client.Produce(confirmCtx, record, func(_ *kgo.Record, err error) { done <- err })
	select {
	case err := <-done:
		if err != nil {
			return apierr.Unavailable("events:unavailable", "The event broker is unavailable.")
		}
		return nil
	case <-confirmCtx.Done():
		return apierr.Unavailable("events:unavailable", "The event broker is unavailable.")
	}
}

var _ seams.MessagePostedPublisher = (*KafkaPublisher)(nil)
