"""The gallery's LYING-TEST exhibit set (05-gallery.md §0.2, §F).

Every test here is real, runnable, and GREEN — that is the whole point. Each is
the default-shaped test an agent ships: it mocks/stubs the very seam that holds
the behavior, then asserts against the stub, so the bug it should catch is
unrepresentable in the test's universe. None drive the real assembled system;
each is paired with its catching test (named in its header). DO NOT COPY these
into real suites.
"""

from __future__ import annotations

from relay import domain


def test_lying_g_taut_message_read_mirrors_the_stub() -> None:
    # LYING TEST (exhibit, do not copy) — gallery case G-TAUT;
    # caught by tests/dm_test.py::test_s_dm_11_list_messages_newest_first.
    # The tautological mock in its purest form: stub the repository to return a
    # canned message, then assert the service returns it. It verifies the stub,
    # never the system (the mirror).
    canned = [
        domain.DmMessage(
            id="m1", conversation_id="c1", sender_id="u1", text="hello", created_at=None
        )
    ]
    repo = _StubMessageRepo(canned)
    got = repo.messages("c1")
    assert len(got) == 1 and got[0].text == "hello"


class _StubMessageRepo:
    def __init__(self, canned: list[domain.DmMessage]) -> None:
        self._canned = canned

    def messages(self, _conversation_id: str) -> list[domain.DmMessage]:
        return self._canned


def test_lying_g_idor_stubbed_guard_passes() -> None:
    # LYING TEST (exhibit, do not copy) — gallery case G-IDOR;
    # caught by tests/dm_test.py::test_s_dm_08_idor_conversation_read.
    # The stubbed authorization guard: force the participant check to true, then
    # "verify" the happy path. The security decision is switched off in the test.
    def guard(_conversation: domain.Conversation, _user_id: str) -> bool:
        return True  # the guard, stubbed open

    conversation = domain.Conversation(id="c1", user_lo="a", user_hi="b", created_at=None)
    assert guard(conversation, "intruder")


def test_lying_g_bola_read_stubbed_membership() -> None:
    # LYING TEST (exhibit, do not copy) — gallery case G-BOLA-READ;
    # caught by tests/channels_test.py::test_s_ch_05_bola_read_private_metadata_404.
    # Stub the membership repository to return a membership; "the caller is a
    # member" is fabricated, so the private-channel bug cannot appear.
    memberships = {"intruder@secret": True}  # fabricated membership
    assert memberships["intruder@secret"]


def test_lying_g_bola_role_hand_built_admin() -> None:
    # LYING TEST (exhibit, do not copy) — gallery case G-BOLA-ROLE;
    # caught by tests/channels_test.py::test_s_ch_11_bola_role_member_adds_403.
    # The test constructs the very authority it should verify: a hand-built admin.
    caller_role = domain.Role.ADMIN  # the test invents the admin it ought to verify
    assert caller_role.at_least(domain.Role.ADMIN)


def test_lying_g_cache_self_consistent_mock() -> None:
    # LYING TEST (exhibit, do not copy) — gallery case G-CACHE;
    # caught by tests/channels_test.py::test_s_ch_16_cache_invalidation_on_kick.
    # Mock the cache as an in-memory map the test keeps consistent with the "DB"
    # by hand — it cannot diverge, so the stale-cache bug is unrepresentable.
    db = {"bob": True}
    cache = {"bob": True}  # kept in lock-step by the test
    del db["bob"]
    del cache["bob"]  # a real bug forgets THIS line; the mock test writes it for you
    assert "bob" not in cache


def test_lying_g_rabbit_verify_published_once() -> None:
    # LYING TEST (exhibit, do not copy) — gallery case G-RABBIT;
    # caught by tests/notifications_test.py::test_s_nt_02_redelivery_is_idempotent.
    # Assert the producer "published exactly once"; the actual at-least-once
    # redelivery semantics never execute against a mock.
    publish_calls = 0

    def publish() -> None:
        nonlocal publish_calls
        publish_calls += 1

    publish()
    assert publish_calls == 1  # delivery semantics never tested


def test_lying_g_race_sequential_returns_same_conversation() -> None:
    # LYING TEST (exhibit, do not copy) — gallery case G-RACE;
    # caught by tests/dm_test.py::test_s_dm_05_concurrent_create_yields_one_row.
    # Single-threaded "creating twice returns the same conversation" — sequential
    # calls can never open the TOCTOU window.
    store: dict[str, str] = {}

    def create(pair: str) -> str:
        if pair not in store:
            store[pair] = "conv-" + pair
        return store[pair]

    assert create("a:b") == create("a:b")  # no window opened, ever


def test_lying_g_tx_verify_the_calls() -> None:
    # LYING TEST (exhibit, do not copy) — gallery case G-TX;
    # caught by tests/dm_test.py::test_s_dm_06_partial_commit_leaves_no_rows.
    # Verify-the-call, not the outcome: assert the saves happened, not that a
    # consistent state exists — the partial-commit bug survives green.
    save_conversation_calls = 0
    save_participant_calls = 0

    def save_conversation() -> None:
        nonlocal save_conversation_calls
        save_conversation_calls += 1

    def save_participant() -> None:
        nonlocal save_participant_calls
        save_participant_calls += 1

    save_conversation()
    save_participant()
    save_participant()
    assert save_conversation_calls == 1 and save_participant_calls == 2  # calls, not atomicity


def test_lying_g_kafka_mocked_bus_is_instant() -> None:
    # LYING TEST (exhibit, do not copy) — gallery case G-KAFKA;
    # caught by tests/feed_test.py::test_s_fd_01_kafka_broker_down_is_503
    # and test_s_fd_05_consumer_idempotent_on_redelivery.
    # A mocked producer always succeeds instantly; the test asserts feed
    # consistency the mock itself fabricated (an in-process synchronous "bus").
    feed: list[str] = []

    def publish_and_project(message_id: str) -> None:
        feed.append(message_id)  # synchronous, never fails, never redelivers

    publish_and_project("m1")
    assert feed == ["m1"]


def test_lying_g_s3_mocked_storage_returns_bytes() -> None:
    # LYING TEST (exhibit, do not copy) — gallery case G-S3;
    # caught by tests/attachments_test.py::test_s_at_06_s3_private_non_member_download_404.
    # Mock the storage client to return bytes and assert the handler returns
    # bytes — the authorization dimension is absent from the test's universe.
    storage = {"key1": b"the-bytes"}
    assert storage["key1"] == b"the-bytes"  # membership never enters the picture


def test_lying_g_llm_mocked_model_returns_summary() -> None:
    # LYING TEST (exhibit, do not copy) — gallery case G-LLM;
    # caught by tests/summary_test.py::test_s_sm_03_llm_prompt_injection_separated
    # and test_s_sm_04_llm_oversized_output_is_502.
    # Mock the LLM client to return "a summary" and assert the endpoint returns
    # it — prompt construction and output validation are both outside the test.
    def model_complete(_prompt: str) -> str:
        return "a summary"

    assert model_complete("anything") == "a summary"


def test_lying_g_http_mocked_client_returns_200() -> None:
    # LYING TEST (exhibit, do not copy) — gallery case G-HTTP;
    # caught by tests/linkpreview_test.py::test_s_lp_02_timeout_degrades_to_null
    # and test_s_lp_04_circuit_breaker_opens.
    # Mock the HTTP client to return 200 instantly — timeouts, sockets, and
    # failure modes cannot occur in the mock's universe.
    def fetch(_url: str) -> dict[str, str]:
        return {"title": "Example"}

    assert fetch("https://example.com")["title"] == "Example"


def test_lying_g_grpc_mocked_full_list() -> None:
    # LYING TEST (exhibit, do not copy) — gallery case G-GRPC;
    # caught by tests/presence_test.py::test_s_pr_04_grpc_partial_stream_is_502.
    # Mock the gRPC client to return a fully-materialized list — streaming (and
    # its failure midway) does not exist in the mock.
    def channel_presence(_user_ids: list[str]) -> list[str]:
        return ["u1", "u2", "u3", "u4", "u5"]

    assert len(channel_presence(["u1", "u2", "u3", "u4", "u5"])) == 5


def test_lying_g_weakval_assertion_mirrors_implementation() -> None:
    # LYING TEST (exhibit, do not copy) — gallery case G-WEAKVAL;
    # caught by tests/pagination_test.py::test_s_pg_01_limit_zero_is_422 .. test_s_pg_04.
    # The after-state of axis-1 gaming: instead of fixing the code, the agent
    # rewrites the pin to mirror whatever the (now too-permissive) impl returns.
    def naive_parse_limit(raw: str) -> int:
        # Buggy: silently clamps instead of 422-ing — the bound is gone.
        value = int(raw)
        return max(1, min(value, 100))

    # The lie: the assertion is weakened to match the clamp, so it stays green.
    assert naive_parse_limit("0") == 1
    assert naive_parse_limit("50000") == 100
