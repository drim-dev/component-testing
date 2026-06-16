package harness

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/infra"
	amqp "github.com/rabbitmq/amqp091-go"
	"github.com/testcontainers/testcontainers-go"
	tcrabbit "github.com/testcontainers/testcontainers-go/modules/rabbitmq"
	"github.com/testcontainers/testcontainers-go/wait"
)

const (
	RabbitQueue      = "notify.dm"
	RabbitNaiveQueue = "notify.dm.naive"
)

// RabbitMqHarness is the RabbitMQ harness (queues / acks / DLQ — different semantics from
// Kafka's log/offsets). Seed = publish a job directly (incl. duplicate and poison); Assert =
// await-until on queue stats (ready via passive declare + unacked via the management API,
// which refreshes on an interval — so "settled" must hold across two spaced samples);
// Reset = purge + drain.
type RabbitMqHarness struct {
	container  *tcrabbit.RabbitMQContainer
	amqpURL    string
	conn       *amqp.Connection
	ch         *amqp.Channel
	mgmtURL    string
	mgmtUser   string
	mgmtPass   string
	httpClient *http.Client
}

func (h *RabbitMqHarness) URL() string { return h.amqpURL }

// Connection exposes the AMQP connection so the fixture can build the app's publisher/worker.
func (h *RabbitMqHarness) Connection() *amqp.Connection { return h.conn }

func (h *RabbitMqHarness) Start(ctx context.Context) error {
	container, err := tcrabbit.Run(ctx, RabbitMqImage,
		testcontainers.WithEnv(map[string]string{
			"RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS": "-rabbit collect_statistics_interval 500",
		}),
		testcontainers.WithWaitStrategy(wait.ForLog("Server startup complete").WithStartupTimeout(120*time.Second)),
	)
	if err != nil {
		return fmt.Errorf("start rabbitmq: %w", err)
	}
	h.container = container

	amqpURL, err := container.AmqpURL(ctx)
	if err != nil {
		return fmt.Errorf("amqp url: %w", err)
	}
	h.amqpURL = amqpURL

	conn, err := amqp.Dial(amqpURL)
	if err != nil {
		return fmt.Errorf("amqp dial: %w", err)
	}
	h.conn = conn
	ch, err := conn.Channel()
	if err != nil {
		return fmt.Errorf("amqp channel: %w", err)
	}
	h.ch = ch
	if err := infra.DeclareNotificationQueues(ch, RabbitQueue); err != nil {
		return fmt.Errorf("declare queues: %w", err)
	}
	if err := infra.DeclareNotificationQueues(ch, RabbitNaiveQueue); err != nil {
		return fmt.Errorf("declare naive queues: %w", err)
	}

	parsed, _ := url.Parse(amqpURL)
	h.mgmtUser = parsed.User.Username()
	h.mgmtPass, _ = parsed.User.Password()
	mgmtHost, err := container.Host(ctx)
	if err != nil {
		return err
	}
	mgmtPort, err := container.MappedPort(ctx, "15672")
	if err != nil {
		return err
	}
	h.mgmtURL = fmt.Sprintf("http://%s:%s", mgmtHost, mgmtPort.Port())
	h.httpClient = &http.Client{Timeout: 5 * time.Second}
	return h.awaitManagementReady(ctx)
}

func (h *RabbitMqHarness) Reset(ctx context.Context) error {
	return h.Drain(ctx)
}

func (h *RabbitMqHarness) Stop(ctx context.Context) error {
	if h.ch != nil {
		_ = h.ch.Close()
	}
	if h.conn != nil {
		_ = h.conn.Close()
	}
	if h.container != nil {
		return h.container.Terminate(ctx)
	}
	return nil
}

// Publish seeds a job directly (a duplicate of a delivered one, or poison).
func (h *RabbitMqHarness) Publish(ctx context.Context, job domain.NotificationJob, queue string) error {
	return h.ch.PublishWithContext(ctx, "", queue, false, false, amqp.Publishing{
		DeliveryMode: amqp.Persistent,
		ContentType:  "application/json",
		Body:         infra.SerializeJob(job),
	})
}

// ReadyCount is the real-time ready count via AMQP passive declare (management stats lag).
func (h *RabbitMqHarness) ReadyCount(ctx context.Context, queue string) (int, error) {
	ch, err := h.conn.Channel()
	if err != nil {
		return 0, err
	}
	defer func() { _ = ch.Close() }()
	q, err := ch.QueueDeclarePassive(queue, true, false, false, false, amqp.Table{"x-queue-type": "quorum"})
	if err != nil {
		return 0, err
	}
	return q.Messages, nil
}

// AwaitSettled blocks until a queue is fully settled: nothing ready AND nothing in flight.
// Unacked is only visible via the management API (interval-refreshed), so the condition must
// hold across TWO samples spaced wider than the 500 ms stats interval.
func (h *RabbitMqHarness) AwaitSettled(ctx context.Context, queue string) error {
	settledSamples := 0
	for settledSamples < 2 {
		ready, err := h.ReadyCount(ctx, queue)
		if err != nil {
			return err
		}
		_, unacked, err := h.queueStats(ctx, queue)
		if err != nil {
			return err
		}
		if ready == 0 && unacked == 0 {
			settledSamples++
			if settledSamples < 2 {
				if err := sleep(ctx, 600*time.Millisecond); err != nil {
					return err
				}
			}
		} else {
			settledSamples = 0
			if err := sleep(ctx, 100*time.Millisecond); err != nil {
				return err
			}
		}
	}
	return nil
}

// AwaitDepth blocks until a (consumer-less, e.g. DLQ) queue holds exactly depth messages.
func (h *RabbitMqHarness) AwaitDepth(ctx context.Context, queue string, depth int) error {
	for {
		ready, err := h.ReadyCount(ctx, queue)
		if err != nil {
			return err
		}
		if ready == depth {
			return nil
		}
		if err := sleep(ctx, 100*time.Millisecond); err != nil {
			return err
		}
	}
}

// Drain purges everything ready then waits out anything in flight, across all four queues.
func (h *RabbitMqHarness) Drain(ctx context.Context) error {
	queues := []string{
		RabbitQueue, infra.DeadLetterQueue(RabbitQueue),
		RabbitNaiveQueue, infra.DeadLetterQueue(RabbitNaiveQueue),
	}
	for {
		settled := true
		for _, queue := range queues {
			ready, err := h.ReadyCount(ctx, queue)
			if err != nil {
				return err
			}
			_, unacked, err := h.queueStats(ctx, queue)
			if err != nil {
				return err
			}
			if ready > 0 {
				if _, err := h.ch.QueuePurge(queue, false); err != nil {
					return err
				}
			}
			settled = settled && ready == 0 && unacked == 0
		}
		if settled {
			return nil
		}
		if err := sleep(ctx, 100*time.Millisecond); err != nil {
			return err
		}
	}
}

func (h *RabbitMqHarness) queueStats(ctx context.Context, queue string) (ready, unacked int, err error) {
	endpoint := fmt.Sprintf("%s/api/queues/%%2F/%s", h.mgmtURL, queue)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return 0, 0, err
	}
	req.SetBasicAuth(h.mgmtUser, h.mgmtPass)
	resp, err := h.httpClient.Do(req)
	if err != nil {
		return 0, 0, err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return 0, 0, nil
	}
	var stats struct {
		Ready   int `json:"messages_ready"`
		Unacked int `json:"messages_unacknowledged"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&stats); err != nil {
		return 0, 0, err
	}
	return stats.Ready, stats.Unacked, nil
}

func (h *RabbitMqHarness) awaitManagementReady(ctx context.Context) error {
	for {
		req, _ := http.NewRequestWithContext(ctx, http.MethodGet, h.mgmtURL+"/api/overview", nil)
		req.SetBasicAuth(h.mgmtUser, h.mgmtPass)
		resp, err := h.httpClient.Do(req)
		if err == nil {
			_ = resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				return nil
			}
		}
		if err := sleep(ctx, 250*time.Millisecond); err != nil {
			return err
		}
	}
}

func sleep(ctx context.Context, d time.Duration) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-time.After(d):
		return nil
	}
}

var _ DependencyHarness = (*RabbitMqHarness)(nil)
