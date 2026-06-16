package harness

import (
	"context"
	"net/http/httptest"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/app"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/idgen"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/workers"
	"github.com/twmb/franz-go/pkg/kgo"
)

// NaiveApp is the §11.D injection mechanism with NO DI framework — the universality proof.
// It builds a SECOND app from the same live infrastructure (the shared containers), then
// hands the caller the assembled Deps to mutate exactly ONE seam to its naive variant before
// the app is wired. Because Deps is a plain struct of interfaces and app.New is a plain
// constructor, "swap one seam" is one field assignment — the same seam the harness already
// uses, expressed in Go's idiom (constructor + interface), not RemoveAll/re-register.
//
// The naive app runs on a DISTINCT IdGen generator (id 1) so its ids never collide with data
// seeded from the default generator (id 0). Its workers are OFF — a naive API host must not
// consume the suite's real topics/queues. Returns a live httptest server (a real socket).
func (f *Fixture) NaiveApp(swap func(deps *app.Deps)) *NaiveAppHandle {
	deps := f.buildCorrectDeps()
	deps.IDs = idgen.New(1)
	swap(&deps)
	a := app.New(deps)
	server := httptest.NewServer(a)
	return &NaiveAppHandle{server: server}
}

// NaiveAppHandle is a disposable naive API host.
type NaiveAppHandle struct{ server *httptest.Server }

// BaseURL is the naive host's address.
func (h *NaiveAppHandle) BaseURL() string { return h.server.URL }

// Close tears the naive host down.
func (h *NaiveAppHandle) Close() { h.server.Close() }

// NaiveWorkers starts a naive consumer-side host (G-KAFKA consumer / G-RABBIT) pointed at the
// PARALLEL topic/group/queue, so its deliberately-buggy processing never races the suite's
// correct consumer. The caller supplies the naive projector and/or recorder (whichever it is
// demonstrating); the other runs correct. The handle MUST be closed at the end of the demo.
func (f *Fixture) NaiveWorkers(ctx context.Context, build func(deps *app.Deps)) *NaiveWorkerHandle {
	deps := f.buildCorrectDeps()
	deps.IDs = idgen.New(1)
	build(&deps)

	consumer, err := kgo.NewClient(
		kgo.SeedBrokers(f.Kafka.Brokers()...),
		kgo.ConsumerGroup(KafkaNaiveGroup),
		kgo.ConsumeTopics(KafkaNaiveTopic),
		kgo.DisableAutoCommit(),
	)
	if err != nil {
		panic(err)
	}

	wctx, cancel := context.WithCancel(context.Background())
	feed := workers.NewFeedConsumer(consumer, deps.Feed, f.log)
	notify := workers.NewNotificationWorker(f.Rabbit.Connection(), deps.NotificationRecord, RabbitNaiveQueue, f.log)
	go feed.Run(wctx)
	go func() { _ = notify.Run(wctx) }()

	return &NaiveWorkerHandle{cancel: cancel, consumer: consumer}
}

// NaiveWorkerHandle is a disposable naive consumer host.
type NaiveWorkerHandle struct {
	cancel   context.CancelFunc
	consumer *kgo.Client
}

// Close stops the naive consumers.
func (h *NaiveWorkerHandle) Close() {
	h.cancel()
	h.consumer.Close()
}
