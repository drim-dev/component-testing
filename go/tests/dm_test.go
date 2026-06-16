package relaytest

import (
	"context"
	"net/http"
	"sync"
	"testing"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/app"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
	"github.com/drim-dev/verifying-agent-code/go/src/relay/idgen"
)

func TestS_DM_01_create_conversation(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	resp := newClient(t, ada.ID).post("/dm/conversations", map[string]string{"recipientId": bob.ID})
	resp.expect(http.StatusCreated)
	var c struct {
		ID             string   `json:"id"`
		ParticipantIDs []string `json:"participantIds"`
	}
	resp.decode(&c)
	lo, hi := domain.NormalizePair(ada.ID, bob.ID)
	if c.ParticipantIDs[0] != lo || c.ParticipantIDs[1] != hi {
		t.Fatalf("participantIds not sorted pair: %v", c.ParticipantIDs)
	}
}

func TestS_DM_02_create_is_idempotent(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	first := newClient(t, ada.ID).post("/dm/conversations", map[string]string{"recipientId": bob.ID})
	first.expect(http.StatusCreated)
	var a struct {
		ID string `json:"id"`
	}
	first.decode(&a)

	second := newClient(t, bob.ID).post("/dm/conversations", map[string]string{"recipientId": ada.ID})
	second.expect(http.StatusOK)
	var b struct {
		ID string `json:"id"`
	}
	second.decode(&b)
	if a.ID != b.ID {
		t.Fatalf("idempotent create returned different ids: %s vs %s", a.ID, b.ID)
	}
}

func TestS_DM_03_create_with_self_is_422(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	newClient(t, ada.ID).post("/dm/conversations", map[string]string{"recipientId": ada.ID}).
		expect(http.StatusUnprocessableEntity).expectCode("dm:recipient:self")
}

func TestS_DM_04_create_with_unknown_recipient_is_404(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	newClient(t, ada.ID).post("/dm/conversations", map[string]string{"recipientId": "NOBODY00000000"}).
		expect(http.StatusNotFound).expectCode("user:not_found")
}

// ---- G-RACE: concurrent create yields exactly one conversation ----

func TestS_DM_05_concurrent_creates_yield_one_conversation(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	assertOneConversation(t, fixture.BaseURL(), ada, bob)
}

func TestS_DM_05_naive_check_then_insert_is_caught(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	naive := fixture.NaiveApp(func(d *app.Deps) {
		d.ConversationWriter = naiveRaceConversationWriter{store: fixture.Store, ids: idgen.New(1)}
	})
	defer naive.Close()
	expectCatchToFail(t, "G-RACE", func(tb testing.TB) {
		assertOneConversationAt(tb, naive.BaseURL(), ada, bob)
	})
}

func assertOneConversation(t *testing.T, baseURL string, ada, bob domain.User) {
	t.Helper()
	assertOneConversationAt(t, baseURL, ada, bob)
}

func assertOneConversationAt(tb testing.TB, baseURL string, ada, bob domain.User) {
	tb.Helper()
	const k = 8
	var wg sync.WaitGroup
	ids := make([]string, k)
	statuses := make([]int, k)
	for i := 0; i < k; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			resp := clientAt(tb, baseURL, ada.ID).post("/dm/conversations", map[string]string{"recipientId": bob.ID})
			statuses[i] = resp.status
			var c struct {
				ID string `json:"id"`
			}
			if resp.status == http.StatusCreated || resp.status == http.StatusOK {
				resp.decode(&c)
			}
			ids[i] = c.ID
		}(i)
	}
	wg.Wait()

	for i, s := range statuses {
		if s != http.StatusCreated && s != http.StatusOK {
			tb.Fatalf("request %d got 5xx/other status %d", i, s)
		}
	}
	first := ids[0]
	for _, id := range ids {
		if id != first || id == "" {
			tb.Fatalf("responses did not share one conversation id: %v", ids)
		}
	}
	lo, hi := domain.NormalizePair(ada.ID, bob.ID)
	n := dbCountTB(tb, "dm_conversations", "user_lo = $1 AND user_hi = $2", lo, hi)
	if n != 1 {
		tb.Fatalf("expected exactly one dm_conversations row, got %d", n)
	}
}

// ---- G-TX: mid-transaction failure leaves no rows ----

func TestS_DM_06_mid_transaction_failure_leaves_no_rows(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	assertTxAtomic(t, fixture.BaseURL(), ada, bob, fixture.Database.ArmParticipantInsertFault)
}

func TestS_DM_06_naive_no_transaction_is_caught(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	naive := fixture.NaiveApp(func(d *app.Deps) {
		d.ConversationWriter = naiveTxConversationWriter{store: fixture.Store, ids: idgen.New(1)}
	})
	defer naive.Close()
	expectCatchToFail(t, "G-TX", func(tb testing.TB) {
		assertTxAtomic(tb, naive.BaseURL(), ada, bob, fixture.Database.ArmParticipantInsertFault)
	})
}

func assertTxAtomic(tb testing.TB, baseURL string, ada, bob domain.User, arm func(context.Context) error) {
	tb.Helper()
	ctx, cancel := bgCtx()
	defer cancel()
	if err := arm(ctx); err != nil {
		tb.Fatalf("arm fault: %v", err)
	}
	resp := clientAt(tb, baseURL, ada.ID).post("/dm/conversations", map[string]string{"recipientId": bob.ID})
	if resp.status != http.StatusInternalServerError {
		tb.Fatalf("expected 500 on mid-tx fault, got %d (%s)", resp.status, resp.rawBody())
	}
	lo, hi := domain.NormalizePair(ada.ID, bob.ID)
	if n := dbCountTB(tb, "dm_conversations", "user_lo = $1 AND user_hi = $2", lo, hi); n != 0 {
		tb.Fatalf("expected zero conversation rows after rollback, got %d", n)
	}
	if n := dbCountTB(tb, "dm_participants", "user_id = $1 OR user_id = $2", ada.ID, bob.ID); n != 0 {
		tb.Fatalf("expected zero participant rows after rollback, got %d", n)
	}
}

func TestS_DM_07_list_returns_only_callers(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	cleo := seedUser(t, "cleo")
	mine := seedConversation(t, ada, bob)
	seedConversation(t, bob, cleo)

	var page struct {
		Items []struct {
			ID string `json:"id"`
		} `json:"items"`
	}
	newClient(t, ada.ID).get("/dm/conversations").expect(http.StatusOK).decode(&page)
	if len(page.Items) != 1 || page.Items[0].ID != mine {
		t.Fatalf("expected only ada's conversation, got %+v", page.Items)
	}
}

// ---- G-IDOR: only participants may read a conversation / its messages ----

func TestS_DM_08_non_participant_conversation_read_is_404(t *testing.T) {
	reset(t)
	conv, _, _, cleo := conversationWithOutsider(t, false)
	assertConversationHidden(t, fixture.BaseURL(), cleo, conv)
}

func TestS_DM_08_naive_read_by_id_is_caught(t *testing.T) {
	reset(t)
	conv, _, _, cleo := conversationWithOutsider(t, false)
	naive := fixture.NaiveApp(func(d *app.Deps) { d.DmAccess = naiveDmAccess{store: fixture.Store} })
	defer naive.Close()
	expectCatchToFail(t, "G-IDOR", func(tb testing.TB) {
		assertConversationHidden(tb, naive.BaseURL(), cleo, conv)
	})
}

func TestS_DM_09_non_participant_messages_read_is_404(t *testing.T) {
	reset(t)
	conv, _, _, cleo := conversationWithOutsider(t, true)
	assertMessagesHidden(t, fixture.BaseURL(), cleo, conv)
}

func TestS_DM_09_naive_message_read_by_id_is_caught(t *testing.T) {
	reset(t)
	conv, _, _, cleo := conversationWithOutsider(t, true)
	naive := fixture.NaiveApp(func(d *app.Deps) { d.DmAccess = naiveDmAccess{store: fixture.Store} })
	defer naive.Close()
	expectCatchToFail(t, "G-IDOR", func(tb testing.TB) {
		assertMessagesHidden(tb, naive.BaseURL(), cleo, conv)
	})
}

func TestS_DM_10_non_participant_message_post_is_404(t *testing.T) {
	reset(t)
	conv, _, _, cleo := conversationWithOutsider(t, false)
	assertPostHidden(t, fixture.BaseURL(), cleo, conv)
}

func TestS_DM_10_naive_message_post_by_id_is_caught(t *testing.T) {
	reset(t)
	conv, _, _, cleo := conversationWithOutsider(t, false)
	naive := fixture.NaiveApp(func(d *app.Deps) { d.DmAccess = naiveDmAccess{store: fixture.Store} })
	defer naive.Close()
	expectCatchToFail(t, "G-IDOR", func(tb testing.TB) {
		assertPostHidden(tb, naive.BaseURL(), cleo, conv)
	})
}

func TestS_DM_11_list_messages_newest_first(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	conv := seedConversation(t, ada, bob)
	for _, text := range []string{"one", "two", "three"} {
		newClient(t, ada.ID).post("/dm/conversations/"+conv+"/messages", map[string]string{"text": text}).
			expect(http.StatusCreated)
	}
	for _, who := range []domain.User{ada, bob} {
		var page struct {
			Items []struct {
				SenderID string `json:"senderId"`
				Text     string `json:"text"`
			} `json:"items"`
		}
		newClient(t, who.ID).get("/dm/conversations/" + conv + "/messages").expect(http.StatusOK).decode(&page)
		if len(page.Items) != 3 || page.Items[0].Text != "three" {
			t.Fatalf("%s: expected 3 newest-first, got %+v", who.Handle, page.Items)
		}
	}
}

func TestS_DM_12_message_text_invalid_is_422(t *testing.T) {
	reset(t)
	ada := seedUser(t, "ada")
	bob := seedUser(t, "bob")
	conv := seedConversation(t, ada, bob)
	long := make([]byte, 4001)
	for i := range long {
		long[i] = 'a'
	}
	newClient(t, ada.ID).post("/dm/conversations/"+conv+"/messages", map[string]string{"text": ""}).
		expect(http.StatusUnprocessableEntity).expectCode("message:text:invalid")
	newClient(t, ada.ID).post("/dm/conversations/"+conv+"/messages", map[string]string{"text": string(long)}).
		expect(http.StatusUnprocessableEntity).expectCode("message:text:invalid")
}

// ---- shared helpers for the G-IDOR triple ----

func conversationWithOutsider(t *testing.T, withMessages bool) (conv string, ada, bob, cleo domain.User) {
	t.Helper()
	ada = seedUser(t, "ada")
	bob = seedUser(t, "bob")
	cleo = seedUser(t, "cleo")
	conv = seedConversation(t, ada, bob)
	if withMessages {
		seedDmMessage(t, ada, conv, "secret one")
		seedDmMessage(t, bob, conv, "secret two")
	}
	return conv, ada, bob, cleo
}

func assertConversationHidden(tb testing.TB, baseURL string, outsider domain.User, conv string) {
	tb.Helper()
	visible := clientAt(tb, baseURL, outsider.ID).get("/dm/conversations/" + conv)
	visible.expect(http.StatusNotFound).expectCode("dm:conversation:not_found")
	unknown := clientAt(tb, baseURL, outsider.ID).get("/dm/conversations/UNKNOWN0000000")
	if visible.rawBody() != unknown.rawBody() {
		tb.Fatalf("existence-hiding body differs:\n visible: %s\n unknown: %s", visible.rawBody(), unknown.rawBody())
	}
}

func assertMessagesHidden(tb testing.TB, baseURL string, outsider domain.User, conv string) {
	tb.Helper()
	resp := clientAt(tb, baseURL, outsider.ID).get("/dm/conversations/" + conv + "/messages")
	resp.expect(http.StatusNotFound).expectCode("dm:conversation:not_found")
}

func assertPostHidden(tb testing.TB, baseURL string, outsider domain.User, conv string) {
	tb.Helper()
	resp := clientAt(tb, baseURL, outsider.ID).post("/dm/conversations/"+conv+"/messages", map[string]string{"text": "intrude"})
	resp.expect(http.StatusNotFound).expectCode("dm:conversation:not_found")
	if n := dbCountTB(tb, "dm_messages", "sender_id = $1", outsider.ID); n != 0 {
		tb.Fatalf("expected no message row written by outsider, got %d", n)
	}
}
