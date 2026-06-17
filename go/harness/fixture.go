package harness

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http/httptest"
	"time"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/app"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/idgen"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/store"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/workers"
	"github.com/twmb/franz-go/pkg/kgo"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// Fixture composes all dependency harnesses for the suite (one Docker host, one suite) and
// builds the assembled Relay app against the real containers. It is the TestFixture-style
// composition the spec calls for; extensibility = add a harness field, not runtime
// re-composition (honest framing — 04-dependencies.md §9).
type Fixture struct {
	Database *DatabaseHarness
	Redis    *RedisHarness
	Kafka    *KafkaHarness
	Rabbit   *RabbitMqHarness
	S3       *S3Harness
	Llm      *LlmHarness
	Unfurl   *UnfurlHarness
	Presence *PresenceHarness

	Store *store.Store
	IDs   *idgen.Factory

	// Live handles the app and workers are built from.
	kafkaConsumer *kgo.Client
	presenceConn  *grpc.ClientConn
	log           *slog.Logger

	// The assembled correct app + its driving httptest server.
	app    *app.App
	server *httptest.Server

	// Worker lifecycle.
	workersCancel context.CancelFunc
	workersDone   chan struct{}
}

// NewFixture constructs the (unstarted) composition.
func NewFixture() *Fixture {
	return &Fixture{
		Database: &DatabaseHarness{},
		Redis:    &RedisHarness{},
		Kafka:    &KafkaHarness{},
		Rabbit:   &RabbitMqHarness{},
		S3:       &S3Harness{},
		Llm:      &LlmHarness{},
		Unfurl:   &UnfurlHarness{},
		log:      slog.New(slog.NewTextHandler(io.Discard, nil)),
	}
}

// Start brings up every dependency, builds the correct app, and starts the workers.
func (f *Fixture) Start(ctx context.Context) error {
	// Real deps that can start in parallel-ish; kept sequential for clear failure messages.
	for _, h := range []DependencyHarness{f.Database, f.Redis, f.Kafka, f.Rabbit, f.S3, f.Llm, f.Unfurl} {
		if err := h.Start(ctx); err != nil {
			return err
		}
	}
	// Presence is a neighbour service, so it is stubbed — it owns no real dependency.
	f.Presence = NewPresenceHarness()
	if err := f.Presence.Start(ctx); err != nil {
		return err
	}

	var err error
	f.Store, err = store.Open(ctx, f.Database.DSN())
	if err != nil {
		return err
	}
	f.IDs = idgen.New(0)

	f.kafkaConsumer, err = kgo.NewClient(
		kgo.SeedBrokers(f.Kafka.Brokers()...),
		kgo.ConsumerGroup(KafkaGroup),
		kgo.ConsumeTopics(KafkaTopic),
		kgo.DisableAutoCommit(),
	)
	if err != nil {
		return err
	}

	f.presenceConn, err = grpc.NewClient(f.Presence.Addr(), grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return err
	}

	deps := f.buildCorrectDeps()
	f.app = app.New(deps)
	f.server = httptest.NewServer(f.app)

	f.startWorkers(deps)
	return nil
}

func (f *Fixture) buildCorrectDeps() app.Deps {
	return app.BuildDeps(app.Infra{
		Store:        f.Store,
		IDs:          f.IDs,
		Redis:        f.Redis.Client(),
		Kafka:        f.kafkaProducer(),
		KafkaTopic:   KafkaTopic,
		Rabbit:       f.Rabbit.Connection(),
		RabbitQueue:  RabbitQueue,
		Minio:        f.S3.Client(),
		PresenceConn: f.presenceConn,
		UnfurlURL:    f.Unfurl.BaseURL(),
		Summary:      f.Llm.Model(),
	})
}

// kafkaProducer returns a producer client for the app (ProduceSync awaits broker confirm).
// RecordDeliveryTimeout bounds confirmation so a paused broker surfaces as the pinned 503
// promptly and deterministically (the broker-down behavior + the zero-flake gate), instead of
// franz-go retrying until the HTTP client gives up.
func (f *Fixture) kafkaProducer() *kgo.Client {
	client, err := kgo.NewClient(
		kgo.SeedBrokers(f.Kafka.Brokers()...),
		kgo.ProducerLinger(0),
		kgo.RecordDeliveryTimeout(3*time.Second),
		kgo.RequestTimeoutOverhead(2*time.Second),
	)
	if err != nil {
		panic(err)
	}
	return client
}

// KafkaProducer exposes a fresh producer client — used by the G-KAFKA-producer naive variant
// to fire-and-forget (the same brokers, the buggy publish shape).
func (f *Fixture) KafkaProducer() *kgo.Client { return f.kafkaProducer() }

func (f *Fixture) startWorkers(deps app.Deps) {
	ctx, cancel := context.WithCancel(context.Background())
	f.workersCancel = cancel
	f.workersDone = make(chan struct{})

	feed := workers.NewFeedConsumer(f.kafkaConsumer, deps.Feed, f.log)
	notify := workers.NewNotificationWorker(f.Rabbit.Connection(), deps.NotificationRecord, RabbitQueue, f.log)

	go func() {
		defer close(f.workersDone)
		go feed.Run(ctx)
		_ = notify.Run(ctx)
	}()
}

// BaseURL is the assembled correct app's address (the real HTTP boundary tests drive).
func (f *Fixture) BaseURL() string { return f.server.URL }

// Reset returns every dependency to a clean state between tests. Brokers are drained BEFORE
// the DB truncate so a late event/job never writes into the next test's clean state.
func (f *Fixture) Reset(ctx context.Context) error {
	if err := f.Kafka.AwaitConsumed(ctx, KafkaTopic, KafkaGroup); err != nil {
		return fmt.Errorf("await kafka idle: %w", err)
	}
	if err := f.Rabbit.Drain(ctx); err != nil {
		return fmt.Errorf("drain rabbit: %w", err)
	}
	if err := f.Database.Reset(ctx); err != nil {
		return err
	}
	if err := f.Redis.Reset(ctx); err != nil {
		return err
	}
	if err := f.S3.Reset(ctx); err != nil {
		return err
	}
	_ = f.Llm.Reset(ctx)
	_ = f.Unfurl.Reset(ctx)
	_ = f.Presence.Reset(ctx)
	return nil
}

// Stop tears everything down at suite end.
func (f *Fixture) Stop(ctx context.Context) error {
	if f.workersCancel != nil {
		f.workersCancel()
		select {
		case <-f.workersDone:
		case <-time.After(10 * time.Second):
		}
	}
	if f.server != nil {
		f.server.Close()
	}
	if f.presenceConn != nil {
		_ = f.presenceConn.Close()
	}
	if f.kafkaConsumer != nil {
		f.kafkaConsumer.Close()
	}
	if f.Store != nil {
		f.Store.Close()
	}
	for _, h := range []DependencyHarness{f.Presence, f.Unfurl, f.Llm, f.S3, f.Rabbit, f.Kafka, f.Redis, f.Database} {
		if h == nil {
			continue
		}
		if err := h.Stop(ctx); err != nil {
			return err
		}
	}
	return nil
}
