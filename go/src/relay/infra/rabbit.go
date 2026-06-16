package infra

import (
	"context"
	"encoding/json"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/seams"
	amqp "github.com/rabbitmq/amqp091-go"
)

// NotifyQueue is the DM notification queue (04-dependencies.md §4).
const NotifyQueue = "notify.dm"

// DeadLetterQueue returns the DLQ name for a queue.
func DeadLetterQueue(queue string) string { return queue + ".dlq" }

// DeclareNotificationQueues declares the quorum queue + its DLQ with the SAME arguments used
// by the worker and the harness (a mismatched redeclare is a channel error). x-delivery-limit
// is a broker-side backstop only — it counts dead-letter republishes, not requeued nacks, so
// the worker enforces the attempt cap itself (see the worker).
func DeclareNotificationQueues(ch *amqp.Channel, queue string) error {
	dlq := DeadLetterQueue(queue)
	if _, err := ch.QueueDeclare(dlq, true, false, false, false, amqp.Table{"x-queue-type": "quorum"}); err != nil {
		return err
	}
	_, err := ch.QueueDeclare(queue, true, false, false, false, amqp.Table{
		"x-queue-type":              "quorum",
		"x-delivery-limit":          int32(2),
		"x-dead-letter-exchange":    "",
		"x-dead-letter-routing-key": dlq,
	})
	return err
}

// SerializeJob / DeserializeJob are the job payload codec — pure functions.
func SerializeJob(job domain.NotificationJob) []byte {
	b, _ := json.Marshal(job)
	return b
}

func DeserializeJob(b []byte) (domain.NotificationJob, error) {
	var job domain.NotificationJob
	err := json.Unmarshal(b, &job)
	return job, err
}

// NotificationJobs publishes a DM notification job in publisher-confirm mode, awaiting the
// broker's confirmation — the pinned post-commit publish (a failure after commit → 500).
type NotificationJobs struct {
	conn  *amqp.Connection
	queue string
}

func NewNotificationJobs(conn *amqp.Connection, queue string) *NotificationJobs {
	return &NotificationJobs{conn: conn, queue: queue}
}

func (n *NotificationJobs) Enqueue(ctx context.Context, job domain.NotificationJob) error {
	ch, err := n.conn.Channel()
	if err != nil {
		return err
	}
	defer func() { _ = ch.Close() }()
	if err := ch.Confirm(false); err != nil {
		return err
	}
	if err := DeclareNotificationQueues(ch, n.queue); err != nil {
		return err
	}
	confirms := ch.NotifyPublish(make(chan amqp.Confirmation, 1))
	if err := ch.PublishWithContext(ctx, "", n.queue, false, false, amqp.Publishing{
		DeliveryMode: amqp.Persistent,
		ContentType:  "application/json",
		Body:         SerializeJob(job),
	}); err != nil {
		return err
	}
	select {
	case confirmation := <-confirms:
		if !confirmation.Ack {
			return amqp.ErrClosed
		}
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

var _ seams.NotificationJobs = (*NotificationJobs)(nil)
