package relaytest

import (
	"net/http"
	"testing"

	"github.com/drim-dev/verifying-agent-code/go/harness"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/app"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/infra"
)

func TestS_NT_01_dm_notifies_recipient_only(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	conv := seedConversation(t, ada, bob)
	newClient(t, ada.ID).post("/dm/conversations/"+conv+"/messages", map[string]string{"text": "hi bob"}).expect(http.StatusCreated)
	awaitNotificationsSettled(t)

	if got := notificationsFor(t, bob); len(got) != 1 || got[0] != "hi bob" {
		t.Fatalf("expected one notification for bob, got %v", got)
	}
	if got := notificationsFor(t, ada); len(got) != 0 {
		t.Fatalf("sender should get no notification, got %v", got)
	}
}

// ---- G-RABBIT: a redelivered job must be treated as success (one row, empty DLQ) ----

func TestS_NT_02_redelivery_is_idempotent(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	conv := seedConversation(t, ada, bob)
	newClient(t, ada.ID).post("/dm/conversations/"+conv+"/messages", map[string]string{"text": "hi bob"}).expect(http.StatusCreated)
	awaitNotificationsSettled(t)

	ctx, cancel := bgCtx()
	defer cancel()
	job := notificationJobFor(t, conv, ada, bob)
	if err := fixture.Rabbit.Publish(ctx, job, harness.RabbitQueue); err != nil {
		t.Fatalf("republish: %v", err)
	}
	awaitNotificationsSettled(t)

	if n := dbCount(t, "notifications", "dm_message_id = $1", job.DmMessageID); n != 1 {
		t.Fatalf("expected exactly one notification row after redelivery, got %d", n)
	}
	if d := dlqDepth(t, harness.RabbitQueue); d != 0 {
		t.Fatalf("DLQ should stay empty after a benign duplicate, got %d", d)
	}
}

func TestS_NT_02_naive_duplicate_crashes_is_caught(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	conv := seedConversation(t, ada, bob)
	newClient(t, ada.ID).post("/dm/conversations/"+conv+"/messages", map[string]string{"text": "hi bob"}).expect(http.StatusCreated)
	awaitNotificationsSettled(t)
	job := notificationJobFor(t, conv, ada, bob)

	ctx, cancel := bgCtx()
	defer cancel()
	naive := fixture.NaiveWorkers(ctx, func(d *app.Deps) {
		d.NotificationRecord = naiveNotificationRecorder{store: fixture.Store, ids: d.IDs}
	})
	defer naive.Close()

	expectCatchToFail(t, "G-RABBIT", func(tb testing.TB) {
		// Seed the row once (as the real worker would), then redeliver to the NAIVE queue: the
		// naive recorder hits the UNIQUE(dm_message_id) constraint, nack-requeues, and after the
		// retry limit the duplicate lands in the naive DLQ.
		if err := fixture.Store.InsertNotification(ctx, domain.Notification{
			ID: fixture.IDs.Create(), UserID: job.RecipientID, DmMessageID: job.DmMessageID,
			ConversationID: job.ConversationID, SenderID: job.SenderID, Preview: job.Preview,
		}); err != nil {
			tb.Fatalf("seed notification: %v", err)
		}
		if err := fixture.Rabbit.Publish(ctx, job, harness.RabbitNaiveQueue); err != nil {
			tb.Fatalf("publish naive: %v", err)
		}
		if err := fixture.Rabbit.AwaitDepth(ctx, infra.DeadLetterQueue(harness.RabbitNaiveQueue), 1); err != nil {
			tb.Fatalf("await naive DLQ: %v", err)
		}
	})
}

func TestS_NT_03_poison_job_dead_letters(t *testing.T) {
	reset(t)
	ctx, cancel := bgCtx()
	defer cancel()
	poison := domain.NotificationJob{
		DmMessageID: "POISON00000001", ConversationID: "CONV0000000001",
		SenderID: "SENDER00000001", RecipientID: "NOBODY00000001", Preview: "boom",
	}
	if err := fixture.Rabbit.Publish(ctx, poison, harness.RabbitQueue); err != nil {
		t.Fatalf("publish poison: %v", err)
	}
	if err := fixture.Rabbit.AwaitDepth(ctx, infra.DeadLetterQueue(harness.RabbitQueue), 1); err != nil {
		t.Fatalf("await DLQ: %v", err)
	}
	if n := dbCount(t, "notifications", "dm_message_id = $1", poison.DmMessageID); n != 0 {
		t.Fatalf("poison job should produce zero notification rows, got %d", n)
	}
}

func TestS_NT_04_poison_then_valid_is_processed(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	conv := seedConversation(t, ada, bob)
	ctx, cancel := bgCtx()
	defer cancel()

	poison := domain.NotificationJob{
		DmMessageID: "POISON00000002", ConversationID: "CONV0000000002",
		SenderID: "SENDER00000002", RecipientID: "NOBODY00000002", Preview: "boom",
	}
	if err := fixture.Rabbit.Publish(ctx, poison, harness.RabbitQueue); err != nil {
		t.Fatalf("publish poison: %v", err)
	}
	newClient(t, ada.ID).post("/dm/conversations/"+conv+"/messages", map[string]string{"text": "still works"}).expect(http.StatusCreated)
	awaitNotificationsSettled(t)

	if got := notificationsFor(t, bob); len(got) != 1 || got[0] != "still works" {
		t.Fatalf("valid job should still be processed despite a poison sibling, got %v", got)
	}
	if err := fixture.Rabbit.AwaitDepth(ctx, infra.DeadLetterQueue(harness.RabbitQueue), 1); err != nil {
		t.Fatalf("poison should be in the DLQ: %v", err)
	}
}

func TestS_NT_05_list_returns_only_callers(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	cleo := seedUser(t, "cleo")
	convAB := seedConversation(t, ada, bob)
	convCB := seedConversation(t, cleo, bob)
	newClient(t, ada.ID).post("/dm/conversations/"+convAB+"/messages", map[string]string{"text": "from ada"}).expect(http.StatusCreated)
	newClient(t, cleo.ID).post("/dm/conversations/"+convCB+"/messages", map[string]string{"text": "from cleo"}).expect(http.StatusCreated)
	awaitNotificationsSettled(t)

	if got := notificationsFor(t, bob); len(got) != 2 {
		t.Fatalf("bob should see both notifications, got %v", got)
	}
	if got := notificationsFor(t, ada); len(got) != 0 {
		t.Fatalf("ada should see none of bob's notifications, got %v", got)
	}
}

// ---- shared notification helpers ----

func awaitNotificationsSettled(t testing.TB) {
	t.Helper()
	ctx, cancel := bgCtx()
	defer cancel()
	if err := fixture.Rabbit.AwaitSettled(ctx, harness.RabbitQueue); err != nil {
		t.Fatalf("await notifications settled: %v", err)
	}
}

func dlqDepth(t testing.TB, queue string) int {
	t.Helper()
	ctx, cancel := bgCtx()
	defer cancel()
	d, err := fixture.Rabbit.ReadyCount(ctx, infra.DeadLetterQueue(queue))
	if err != nil {
		t.Fatalf("dlq depth: %v", err)
	}
	return d
}

func notificationsFor(t testing.TB, who domain.User) []string {
	t.Helper()
	var page struct {
		Items []struct {
			Preview string `json:"preview"`
		} `json:"items"`
	}
	clientAt(t, fixture.BaseURL(), who.ID).get("/notifications").expect(http.StatusOK).decode(&page)
	out := []string{}
	for _, it := range page.Items {
		out = append(out, it.Preview)
	}
	return out
}

func notificationJobFor(t *testing.T, conv string, sender, recipient domain.User) domain.NotificationJob {
	t.Helper()
	ctx, cancel := bgCtx()
	defer cancel()
	msgs, err := fixture.Store.DmMessages(ctx, conv, "", 1)
	if err != nil || len(msgs) == 0 {
		t.Fatalf("load dm message: %v (n=%d)", err, len(msgs))
	}
	m := msgs[0]
	return domain.NotificationJob{
		DmMessageID: m.ID, ConversationID: conv, SenderID: sender.ID,
		RecipientID: recipient.ID, Preview: domain.Preview(m.Text),
	}
}
