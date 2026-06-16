package relaytest

import (
	"net/http"
	"testing"

	"github.com/drim-dev/verifying-agent-code/go/harness"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/app"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
)

// ---- G-KAFKA producer: a post must await broker confirmation; broker down → 503, no row ----

func TestS_FD_01_broker_down_post_is_503(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	ch := seedChannel(t, ada, "team", false)
	assertBrokerDownBlocksPost(t, fixture.BaseURL(), ada, ch)
}

func TestS_FD_01_naive_fire_and_forget_is_caught(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	ch := seedChannel(t, ada, "team", false)
	naive := fixture.NaiveApp(func(d *app.Deps) { d.Publisher = naiveKafkaPublisher{client: fixture.KafkaProducer()} })
	defer naive.Close()
	expectCatchToFail(t, "G-KAFKA", func(tb testing.TB) {
		assertBrokerDownBlocksPost(tb, naive.BaseURL(), ada, ch)
	})
}

func assertBrokerDownBlocksPost(tb testing.TB, baseURL string, sender domain.User, channelID string) {
	tb.Helper()
	ctx, cancel := bgCtx()
	defer cancel()
	if err := fixture.Kafka.StopBroker(ctx); err != nil {
		tb.Fatalf("stop broker: %v", err)
	}
	defer func() {
		startCtx, startCancel := bgCtx()
		defer startCancel()
		if err := fixture.Kafka.StartBroker(startCtx); err != nil {
			tb.Fatalf("restart broker: %v", err)
		}
	}()

	resp := clientAt(tb, baseURL, sender.ID).post("/channels/"+channelID+"/messages", map[string]any{"text": "while down"})
	resp.expect(http.StatusServiceUnavailable).expectCode("events:unavailable")
	if n := dbCountTB(tb, "channel_messages", "channel_id = $1", channelID); n != 0 {
		tb.Fatalf("expected no channel_messages row when the broker is down, got %d", n)
	}
}

func TestS_FD_02_fanout_to_members_except_sender(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	cleo := seedUser(t, "cleo")
	ch := seedChannel(t, ada, "team", false)
	seedJoin(t, bob, ch)
	seedJoin(t, cleo, ch)

	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": "team news"}).expect(http.StatusCreated)
	awaitFeedSettled(t)

	for _, who := range []domain.User{bob, cleo} {
		entries := feedEntriesFor(t, who, ch)
		if len(entries) != 1 || entries[0] != "team news" {
			t.Fatalf("%s expected one feed entry, got %v", who.Handle, entries)
		}
	}
	if len(feedEntriesFor(t, ada, ch)) != 0 {
		t.Fatalf("sender should not get a feed entry")
	}
}

func TestS_FD_03_unread_increments(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	ch := seedChannel(t, ada, "team", false)
	seedJoin(t, bob, ch)

	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": "one"}).expect(http.StatusCreated)
	awaitFeedSettled(t)
	if c := unreadFor(t, bob, ch); c != 1 {
		t.Fatalf("expected unread 1, got %d", c)
	}
	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": "two"}).expect(http.StatusCreated)
	awaitFeedSettled(t)
	if c := unreadFor(t, bob, ch); c != 2 {
		t.Fatalf("expected unread 2, got %d", c)
	}
}

func TestS_FD_04_mark_read_resets_only_that_channel(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	a := seedChannel(t, ada, "alpha", false)
	b := seedChannel(t, ada, "beta", false)
	seedJoin(t, bob, a)
	seedJoin(t, bob, b)
	newClient(t, ada.ID).post("/channels/"+a+"/messages", map[string]any{"text": "a1"}).expect(http.StatusCreated)
	newClient(t, ada.ID).post("/channels/"+b+"/messages", map[string]any{"text": "b1"}).expect(http.StatusCreated)
	awaitFeedSettled(t)

	newClient(t, bob.ID).post("/channels/"+a+"/read", nil).expect(http.StatusNoContent)
	if c := unreadFor(t, bob, a); c != 0 {
		t.Fatalf("channel a unread should be 0, got %d", c)
	}
	if c := unreadFor(t, bob, b); c != 1 {
		t.Fatalf("channel b unread should be untouched at 1, got %d", c)
	}
}

// ---- G-KAFKA consumer: redelivery must not double-count feed/unread ----

func TestS_FD_05_redelivery_is_idempotent(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	ch := seedChannel(t, ada, "team", false)
	seedJoin(t, bob, ch)
	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": "once"}).expect(http.StatusCreated)
	awaitFeedSettled(t)

	ctx, cancel := bgCtx()
	defer cancel()
	ev := redeliveryEvent(t, ch, ada)

	// Correct consumer: re-publishing the same event leaves exactly one feed entry and unread 1.
	if err := fixture.Kafka.Publish(ctx, ev, harness.KafkaTopic); err != nil {
		t.Fatalf("republish: %v", err)
	}
	awaitFeedSettled(t)
	if got := len(feedEntriesFor(t, bob, ch)); got != 1 {
		t.Fatalf("expected one feed entry after redelivery, got %d", got)
	}
	if c := unreadFor(t, bob, ch); c != 1 {
		t.Fatalf("expected unread still 1 after redelivery, got %d", c)
	}
}

func TestS_FD_05_naive_unconditional_increment_is_caught(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	ch := seedChannel(t, ada, "team", false)
	seedJoin(t, bob, ch)
	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": "once"}).expect(http.StatusCreated)
	awaitFeedSettled(t)

	ctx, cancel := bgCtx()
	defer cancel()
	naive := fixture.NaiveWorkers(ctx, func(d *app.Deps) {
		d.Feed = naiveFeedProjector{store: fixture.Store, unread: d.Unread, ids: d.IDs}
	})
	defer naive.Close()

	ev := redeliveryEvent(t, ch, ada)
	expectCatchToFail(t, "G-KAFKA", func(tb testing.TB) {
		// Two deliveries to the NAIVE topic; the naive projector increments unconditionally so
		// the unread counter runs ahead of the (deduplicated) feed.
		if err := fixture.Kafka.Publish(ctx, ev, harness.KafkaNaiveTopic); err != nil {
			tb.Fatalf("publish 1: %v", err)
		}
		if err := fixture.Kafka.AwaitConsumed(ctx, harness.KafkaNaiveTopic, harness.KafkaNaiveGroup); err != nil {
			tb.Fatalf("await 1: %v", err)
		}
		if err := fixture.Kafka.Publish(ctx, ev, harness.KafkaNaiveTopic); err != nil {
			tb.Fatalf("publish 2: %v", err)
		}
		if err := fixture.Kafka.AwaitConsumed(ctx, harness.KafkaNaiveTopic, harness.KafkaNaiveGroup); err != nil {
			tb.Fatalf("await 2: %v", err)
		}
		if c := unreadFor(tb, bob, ch); c != 1 {
			tb.Fatalf("naive double-count: unread should diverge from 1, got %d", c)
		}
	})
}

// ---- G-CACHE: a kicked member receives no further feed entries ----

func TestS_FD_06_kicked_member_gets_no_feed(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	ch := seedChannel(t, ada, "team", false)
	seedJoin(t, bob, ch)
	newClient(t, ada.ID).del("/channels/" + ch + "/members/" + bob.ID).expect(http.StatusNoContent)

	newClient(t, ada.ID).post("/channels/"+ch+"/messages", map[string]any{"text": "after kick"}).expect(http.StatusCreated)
	awaitFeedSettled(t)
	if got := len(feedEntriesFor(t, bob, ch)); got != 0 {
		t.Fatalf("kicked member should get no feed entry, got %d", got)
	}
	if c := unreadFor(t, bob, ch); c != 0 {
		t.Fatalf("kicked member unread should not increment, got %d", c)
	}
}

// ---- shared feed helpers ----

func awaitFeedSettled(t testing.TB) {
	t.Helper()
	ctx, cancel := bgCtx()
	defer cancel()
	if err := fixture.Kafka.AwaitConsumed(ctx, harness.KafkaTopic, harness.KafkaGroup); err != nil {
		t.Fatalf("await feed settled: %v", err)
	}
}

func feedEntriesFor(t testing.TB, who domain.User, channelID string) []string {
	t.Helper()
	var page struct {
		Items []struct {
			ChannelID string `json:"channelId"`
			Preview   string `json:"preview"`
		} `json:"items"`
	}
	clientAt(t, fixture.BaseURL(), who.ID).get("/feed").expect(http.StatusOK).decode(&page)
	previews := []string{}
	for _, it := range page.Items {
		if it.ChannelID == channelID {
			previews = append(previews, it.Preview)
		}
	}
	return previews
}

func unreadFor(t testing.TB, who domain.User, channelID string) int64 {
	t.Helper()
	var body struct {
		Channels map[string]int64 `json:"channels"`
	}
	clientAt(t, fixture.BaseURL(), who.ID).get("/me/unread").expect(http.StatusOK).decode(&body)
	return body.Channels[channelID]
}

// redeliveryEvent builds the message.posted event for the single message posted to channelID,
// so the test can republish it (exercising the consumer's idempotency directly).
func redeliveryEvent(t *testing.T, channelID string, sender domain.User) domain.MessagePosted {
	t.Helper()
	ctx, cancel := bgCtx()
	defer cancel()
	msgs, err := fixture.Store.ChannelMessages(ctx, channelID, "", 1)
	if err != nil || len(msgs) == 0 {
		t.Fatalf("load posted message: %v (n=%d)", err, len(msgs))
	}
	m := msgs[0]
	return domain.MessagePosted{
		MessageID: m.ID, ChannelID: channelID, SenderID: sender.ID,
		Preview: domain.Preview(m.Text), PostedAt: m.CreatedAt,
	}
}
