// Package app is Relay's composition root and HTTP layer. The whole DI story is one struct:
// Deps holds every seam (05-gallery §0.4) plus the infrastructure ports. New(Deps) wires
// the chi router. There is NO DI framework — the seam is a constructor argument, and a
// test injects a naive variant by building Deps with one field swapped (see NewApp in the
// harness). This is the universality proof: the same five-brick pattern lands with plain
// constructor + interface injection.
package app

import (
	"github.com/drim-dev/verifying-agent-code/go/src/relay/idgen"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/seams"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/store"
)

// Deps is the complete dependency set the app is constructed from. Each field is an
// interface (a seam) or an infrastructure handle. The CORRECT implementations are assembled
// in Build; a test rebuilds Deps with exactly one seam replaced by its naive variant.
type Deps struct {
	Store *store.Store
	IDs   *idgen.Factory

	// Gallery seams (the injectable bugs).
	DmAccess           seams.DmAccess
	ConversationWriter seams.ConversationWriter
	ChannelReadGate    seams.ChannelReadGate
	ChannelRoleGate    seams.ChannelRoleGate
	MembershipWriter   seams.MembershipWriter
	Publisher          seams.MessagePostedPublisher
	NotificationRecord seams.NotificationRecorder
	Presence           seams.PresenceClient
	LinkPreviewer      seams.LinkPreviewer
	AttachmentAccess   seams.AttachmentAccess

	// Infrastructure ports.
	Cache      seams.MembershipCache
	Unread     seams.UnreadCounters
	Jobs       seams.NotificationJobs
	Store3     seams.AttachmentStore
	Summary    seams.SummaryModel  // the LLM port (the fake attaches here)
	Summarizer seams.Summarizer    // the G-LLM seam (assemble + validate)
	Feed       seams.FeedProjector // used by the consumer; injectable for the G-KAFKA consumer naive
	Heartbeats seams.Heartbeats
}
