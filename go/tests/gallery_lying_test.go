package relaytest

// This file is the gallery's LYING-TEST exhibit set (05-gallery.md §0.2, §3). Every test here
// is real, runnable, and GREEN — that is the whole point. Each is the default-shaped test an
// agent ships: it mocks/stubs the very seam that holds the behavior, then asserts against the
// stub, so the bug it should catch is unrepresentable in the test's universe. None of these
// drive the real assembled system; each is paired in the README with the catching test that
// DOES (named in its header). Do not copy these into real suites.

import (
	"context"
	"testing"

	"github.com/drim-dev/verifying-agent-code/go/src/relay/domain"
)

// LYING TEST (exhibit, do not copy) — gallery case G-TAUT; caught by TestS_DM_11_list_messages_newest_first.
// The tautological mock in its purest form: stub the repository to return a canned message,
// then assert the service returns it. It verifies the stub, never the system (the mirror).
func TestLying_G_TAUT_message_read_mirrors_the_stub(t *testing.T) {
	type messageRepo interface {
		Messages(conversationID string) []domain.DmMessage
	}
	canned := []domain.DmMessage{{ID: "m1", ConversationID: "c1", SenderID: "u1", Text: "hello"}}
	var repo messageRepo = stubMessageRepo{canned: canned}

	got := repo.Messages("c1")

	if len(got) != 1 || got[0].Text != "hello" {
		t.Fatalf("expected the canned message back")
	}
	// Green by construction: the assertion mirrors exactly what the stub was told to return.
}

type stubMessageRepo struct{ canned []domain.DmMessage }

func (s stubMessageRepo) Messages(string) []domain.DmMessage { return s.canned }

// LYING TEST (exhibit, do not copy) — gallery case G-IDOR; caught by TestS_DM_08_non_participant_conversation_read_is_404.
// The stubbed authorization guard: force the participant check to true, then "verify" the happy
// path returns the conversation. The security decision is switched off inside the test.
func TestLying_G_IDOR_stubbed_guard_passes(t *testing.T) {
	guard := func(_ domain.Conversation, _ string) bool { return true } // the guard, stubbed open
	conv := domain.Conversation{ID: "c1", UserLo: "a", UserHi: "b"}

	allowed := guard(conv, "intruder")

	if !allowed {
		t.Fatalf("expected the (stubbed) guard to allow access")
	}
}

// LYING TEST (exhibit, do not copy) — gallery case G-BOLA-READ; caught by TestS_CH_05_private_metadata_hidden.
// Stub the membership repository to return a membership, then assert messages come back —
// "the caller is a member" is fabricated by the test, so the private-channel bug cannot appear.
func TestLying_G_BOLA_READ_stubbed_membership(t *testing.T) {
	memberships := map[string]bool{"intruder@secret": true} // fabricated membership
	isMember := memberships["intruder@secret"]

	if !isMember {
		t.Fatalf("expected the stubbed membership to be present")
	}
}

// LYING TEST (exhibit, do not copy) — gallery case G-BOLA-ROLE; caught by TestS_CH_11_member_add_is_403.
// The test constructs the very authority it should verify: a hand-built "admin" context.
func TestLying_G_BOLA_ROLE_hand_built_admin(t *testing.T) {
	type ctx struct{ role domain.Role }
	caller := ctx{role: domain.RoleAdmin} // the test invents the admin it ought to verify

	if !caller.role.AtLeast(domain.RoleAdmin) {
		t.Fatalf("expected the hand-built admin to pass the role check")
	}
}

// LYING TEST (exhibit, do not copy) — gallery case G-CACHE; caught by TestS_CH_16_kick_invalidates_membership_cache.
// Mock the cache as an in-memory map the test keeps consistent with the "DB" by hand. A mock
// cache cannot diverge from the DB, so the stale-cache-after-removal bug is unrepresentable.
func TestLying_G_CACHE_self_consistent_mock(t *testing.T) {
	db := map[string]bool{"bob": true}
	cache := map[string]bool{"bob": true} // the test keeps these two in lock-step

	delete(db, "bob")
	delete(cache, "bob") // a real bug forgets THIS line; the mock test writes it for you

	if cache["bob"] {
		t.Fatalf("expected the mock cache to agree with the DB")
	}
}

// LYING TEST (exhibit, do not copy) — gallery case G-RACE; caught by TestS_DM_05_concurrent_creates_yield_one_conversation.
// Single-threaded "creating twice returns the same conversation" — sequential calls can never
// open the TOCTOU window the real concurrency bug needs.
func TestLying_G_RACE_sequential_create(t *testing.T) {
	store := map[string]string{}
	create := func(pair string) string {
		if id, ok := store[pair]; ok {
			return id
		}
		store[pair] = "conv-" + pair
		return store[pair]
	}

	first := create("a:b")
	second := create("a:b") // sequential — the window never opens

	if first != second {
		t.Fatalf("expected the same conversation id on the second sequential create")
	}
}

// LYING TEST (exhibit, do not copy) — gallery case G-TX; caught by TestS_DM_06_mid_transaction_failure_leaves_no_rows.
// Verify-the-call, not the outcome: assert the saves were invoked the right number of times,
// never that a consistent state exists. The partial-commit bug survives green.
func TestLying_G_TX_verify_calls_not_outcome(t *testing.T) {
	spy := &saveSpy{}
	spy.saveConversation()
	spy.saveParticipant()
	spy.saveParticipant()

	if spy.conversations != 1 || spy.participants != 2 {
		t.Fatalf("expected 1 conversation save and 2 participant saves")
	}
	// No assertion that the three saves are atomic — exactly what the bug exploits.
}

type saveSpy struct{ conversations, participants int }

func (s *saveSpy) saveConversation() { s.conversations++ }
func (s *saveSpy) saveParticipant()  { s.participants++ }

// LYING TEST (exhibit, do not copy) — gallery case G-KAFKA; caught by TestS_FD_01_broker_down_post_is_503.
// A mocked producer that always succeeds instantly, then asserts feed consistency the mock
// itself fabricated. Broker-down and redelivery semantics never execute.
func TestLying_G_KAFKA_mock_producer_always_succeeds(t *testing.T) {
	published := 0
	publish := func(domain.MessagePosted) error { published++; return nil } // never fails

	_ = publish(domain.MessagePosted{MessageID: "m1", ChannelID: "c1"})

	if published != 1 {
		t.Fatalf("expected the mock producer to record one publish")
	}
}

// LYING TEST (exhibit, do not copy) — gallery case G-RABBIT; caught by TestS_NT_02_redelivery_is_idempotent.
// "Published exactly once" against a mocked broker — the at-least-once redelivery the
// idempotency must survive never executes, so the duplicate-handling bug is invisible.
func TestLying_G_RABBIT_verify_published_once(t *testing.T) {
	publishes := 0
	publish := func(domain.NotificationJob) { publishes++ } // a mock that is never redelivered

	publish(domain.NotificationJob{DmMessageID: "m1"})

	if publishes != 1 {
		t.Fatalf("expected the producer to have published exactly once")
	}
}

// LYING TEST (exhibit, do not copy) — gallery case G-S3; caught by TestS_AT_06_private_download_hidden.
// Mock the storage client to return bytes and assert the handler returns bytes — the
// authorization dimension is simply absent from the test's universe.
func TestLying_G_S3_mock_returns_bytes(t *testing.T) {
	get := func(_ string) []byte { return []byte("file-bytes") } // possession of the key = bytes

	got := get("any/key")

	if string(got) != "file-bytes" {
		t.Fatalf("expected the mocked bytes back")
	}
}

// LYING TEST (exhibit, do not copy) — gallery case G-LLM; caught by TestS_SM_03_hostile_text_stays_in_data_block.
// Mock the LLM client to return "a summary" and assert the endpoint returns it. Prompt
// construction and output validation are both outside the test's universe.
func TestLying_G_LLM_mock_returns_a_summary(t *testing.T) {
	complete := func(context.Context, string) (string, error) { return "a summary", nil }

	out, _ := complete(context.Background(), "anything at all, validated by no one")

	if out != "a summary" {
		t.Fatalf("expected the mocked summary back")
	}
}

// LYING TEST (exhibit, do not copy) — gallery case G-HTTP; caught by TestS_LP_02_slow_unfurl_degrades_within_deadline.
// Mock the HTTP client to return 200 instantly — timeouts, sockets, and failure modes cannot
// occur in the mock's universe, so neither the hang nor the breaker is ever exercised.
func TestLying_G_HTTP_mock_returns_200_instantly(t *testing.T) {
	fetch := func(string) (string, error) { return "Example", nil } // no latency, no failure

	title, _ := fetch("https://example.com")

	if title != "Example" {
		t.Fatalf("expected the mocked title back")
	}
}

// LYING TEST (exhibit, do not copy) — gallery case G-GRPC; caught by TestS_PR_04_partial_stream_is_502.
// Mock the gRPC client to return a fully-materialized list — streaming, and its failure midway,
// does not exist in the mock, so the partial-stream-as-complete bug is invisible.
func TestLying_G_GRPC_mock_materialized_list(t *testing.T) {
	channelPresence := func() []domain.PresenceStatus { // no stream, no mid-stream error
		return []domain.PresenceStatus{{UserID: "a", Online: true}, {UserID: "b", Online: false}}
	}

	got := channelPresence()

	if len(got) != 2 {
		t.Fatalf("expected the mocked materialized list")
	}
}

// LYING TEST (exhibit, do not copy) — gallery case G-WEAKVAL; caught by TestS_PG_01_limit_zero_is_422 … 04.
// Axis-1 gaming: instead of fixing the code to reject limit=0, the agent rewrites the pinning
// test to mirror whatever the (lenient) implementation returns. The test now passes by agreeing
// with the bug. Shown here as the weakened "after" state.
func TestLying_G_WEAKVAL_assertion_weakened_to_match_impl(t *testing.T) {
	lenientClamp := func(limit int) int { // the buggy impl silently clamps instead of 422-ing
		if limit < 1 {
			return 50
		}
		return limit
	}

	effective := lenientClamp(0)

	// The pin SHOULD be "limit=0 → 422". Weakened, the assertion just mirrors the clamp:
	if effective != 50 {
		t.Fatalf("expected the implementation's clamped value (the weakened pin)")
	}
}
