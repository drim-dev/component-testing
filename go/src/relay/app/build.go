package app

import (
	"context"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/apierr"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/idgen"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/infra"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/presence"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/seams"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/store"
	"github.com/minio/minio-go/v7"
	amqp "github.com/rabbitmq/amqp091-go"
	"github.com/redis/go-redis/v9"
	"github.com/twmb/franz-go/pkg/kgo"
	"google.golang.org/grpc"
)

// Infra is the set of live infrastructure handles the composition root wires the CORRECT
// seams from. This is the one place all the bricks meet — and the place a test swaps exactly
// one correct seam for its naive variant (by editing the returned Deps before calling New).
type Infra struct {
	Store        *store.Store
	IDs          *idgen.Factory
	Redis        *redis.Client
	Kafka        *kgo.Client
	KafkaTopic   string
	Rabbit       *amqp.Connection
	RabbitQueue  string
	Minio        *minio.Client
	PresenceConn grpc.ClientConnInterface
	UnfurlURL    string

	// Summary is the LLM port. Production leaves it nil (no model configured); the test
	// harness passes its interaction-verifying fake here.
	Summary seams.SummaryModel
}

// BuildDeps assembles the complete CORRECT dependency set from live infrastructure. The
// returned Deps is the universality artifact: every seam is a plain constructor call, no DI
// framework. A test calls BuildDeps, replaces one field with a naive variant, then New(deps).
func BuildDeps(in Infra) Deps {
	cache := infra.NewMembershipCache(in.Redis)
	unread := infra.NewUnreadCounters(in.Redis)

	var summaryModel seams.SummaryModel = notConfiguredSummary{}
	if in.Summary != nil {
		summaryModel = in.Summary
	}

	d := Deps{
		Store:              in.Store,
		IDs:                in.IDs,
		DmAccess:           NewDmAccess(in.Store),
		ConversationWriter: NewConversationWriter(in.Store, in.IDs),
		ChannelReadGate:    NewChannelReadGate(in.Store),
		ChannelRoleGate:    NewChannelRoleGate(in.Store),
		MembershipWriter:   NewMembershipWriter(in.Store, cache),
		Publisher:          infra.NewKafkaPublisher(in.Kafka, in.KafkaTopic),
		NotificationRecord: NewNotificationRecorder(in.Store, in.IDs),
		Presence:           presence.NewClient(in.PresenceConn),
		LinkPreviewer:      infra.NewLinkPreviewer(in.Redis, in.UnfurlURL),
		AttachmentAccess:   NewAttachmentAccess(in.Store),
		Cache:              cache,
		Unread:             unread,
		Jobs:               infra.NewNotificationJobs(in.Rabbit, in.RabbitQueue),
		Store3:             infra.NewS3Store(in.Minio),
		Summary:            summaryModel,
		Feed:               NewFeedProjector(in.Store, unread, in.IDs),
		Heartbeats:         infra.NewHeartbeats(in.Redis),
	}
	d.Summarizer = NewSummarizer(d.Summary)
	return d
}

// notConfiguredSummary is the production default for the LLM port: the companion ships
// without model credentials (the port is the architectural boundary; the test harness
// attaches an interaction-verifying fake here). 503 if ever called unconfigured.
type notConfiguredSummary struct{}

func (notConfiguredSummary) Complete(_ context.Context, _ domain.SummaryRequest) (string, error) {
	return "", apierr.Unavailable("summary:unconfigured", "No summary model is configured.")
}
