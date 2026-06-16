package workers

import (
	"context"
	"log/slog"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/infra"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/seams"
	amqp "github.com/rabbitmq/amqp091-go"
)

// MaxAttempts is the delivery cap before a job is dead-lettered.
const MaxAttempts = 3

// NotificationWorker consumes notify.dm with manual acks, prefetch 1: a job is acked only
// after the recorder persists its effect. A failing job is retried up to MaxAttempts, then
// dead-lettered (a final requeue:false nack routes it to the DLX → DLQ deterministically),
// so the queue keeps flowing past a poison job.
//
// The attempt cap is enforced here via x-acquired-count (the header a quorum queue stamps on
// a requeued nack), NOT by leaning on the broker's x-delivery-limit (which counts dead-letter
// republishes, not requeued nacks, so it would loop). The correct recorder treats a
// redelivered duplicate as success → ack, so a duplicate never crash-loops; the naive variant
// does not, so a redelivered duplicate dead-letters after MaxAttempts.
type NotificationWorker struct {
	conn     *amqp.Connection
	recorder seams.NotificationRecorder
	queue    string
	log      *slog.Logger
}

func NewNotificationWorker(conn *amqp.Connection, recorder seams.NotificationRecorder, queue string, log *slog.Logger) *NotificationWorker {
	return &NotificationWorker{conn: conn, recorder: recorder, queue: queue, log: log}
}

// Run consumes until ctx is cancelled.
func (w *NotificationWorker) Run(ctx context.Context) error {
	ch, err := w.conn.Channel()
	if err != nil {
		return err
	}
	defer func() { _ = ch.Close() }()
	if err := infra.DeclareNotificationQueues(ch, w.queue); err != nil {
		return err
	}
	if err := ch.Qos(1, 0, false); err != nil {
		return err
	}
	deliveries, err := ch.Consume(w.queue, "", false, false, false, false, nil)
	if err != nil {
		return err
	}

	for {
		select {
		case <-ctx.Done():
			return nil
		case delivery, ok := <-deliveries:
			if !ok {
				return nil
			}
			w.handle(ctx, delivery)
		}
	}
}

func (w *NotificationWorker) handle(ctx context.Context, delivery amqp.Delivery) {
	job, err := infra.DeserializeJob(delivery.Body)
	if err == nil {
		err = w.recorder.Record(ctx, job)
	}
	if err == nil {
		_ = delivery.Ack(false)
		return
	}
	exhausted := acquiredCount(delivery) >= MaxAttempts
	w.log.Debug("notification job failed", "attempt", acquiredCount(delivery), "exhausted", exhausted, "err", err)
	// On the final attempt nack with requeue:false → DLX → DLQ; otherwise requeue.
	_ = delivery.Nack(false, !exhausted)
}

// acquiredCount returns this delivery attempt (1-based). A quorum queue stamps
// x-acquired-count = prior acquisitions on a requeued nack (absent on first delivery).
func acquiredCount(delivery amqp.Delivery) int64 {
	if delivery.Headers == nil {
		return 1
	}
	raw, ok := delivery.Headers["x-acquired-count"]
	if !ok {
		return 1
	}
	switch v := raw.(type) {
	case int64:
		return v + 1
	case int32:
		return int64(v) + 1
	case int:
		return int64(v) + 1
	default:
		return 1
	}
}
