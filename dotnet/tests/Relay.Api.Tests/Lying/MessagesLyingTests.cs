using FluentAssertions;
using Relay.Api.Common.Auth;
using Relay.Api.Domain.Messages;
using Relay.Api.Features.Messages;

namespace Relay.Api.Tests.Lying;

/// <summary>
/// LYING TESTS (exhibits, do not copy). Each is a real, runnable, GREEN test that an
/// agent's default mock-style would produce — and each verifies a stub or a pure
/// predicate, never the assembled system. They exist only as paired exhibits for their
/// catching tests (spec/05-gallery.md §0.2) and are the "mirror" the guide's §3 lifts up.
/// </summary>
public sealed class MessagesLyingTests
{
    // LYING TEST (exhibit, do not copy) — gallery case G-IDOR;
    // caught by DmReadTests.S_DM_08_non_participant_conversation_read_is_404
    [Fact]
    public async Task IdorLyingTest_stubbed_guard_blesses_any_caller()
    {
        var conversation = new DmConversation { Id = "c1", UserLo = "a", UserHi = "b" };
        IDmAccess access = new AlwaysGrantsDmAccess(conversation);
        var caller = new CurrentUser();
        caller.Set("intruder-who-is-not-a-participant");
        var handler = new GetConversation.RequestHandler(caller, access);

        var result = await handler.Handle(new GetConversation.Request("c1"), CancellationToken.None);

        result.Id.Should().Be("c1"); // green — yet a non-participant just "read" the conversation
    }

    // LYING TEST (exhibit, do not copy) — gallery case G-RACE;
    // caught by CreateConversationTests.S_DM_05_concurrent_creates_yield_one_conversation
    [Fact]
    public async Task RaceLyingTest_sequential_calls_never_open_the_window()
    {
        var conversation = new DmConversation { Id = "c1", UserLo = "a", UserHi = "b" };
        IConversationWriter writer = new IdempotentFakeWriter(conversation);

        var first = await writer.Create("a", "b", CancellationToken.None);
        var second = await writer.Create("a", "b", CancellationToken.None);

        first.Conversation.Id.Should().Be(second.Conversation.Id); // green — concurrency is never exercised
    }

    // LYING TEST (exhibit, do not copy) — gallery case G-TX;
    // caught by CreateConversationTests.S_DM_06_mid_transaction_failure_leaves_no_rows
    [Fact]
    public async Task TxLyingTest_verifies_the_call_not_the_persisted_state()
    {
        var writer = new RecordingConversationWriter();

        await writer.Create("a", "b", CancellationToken.None);

        writer.Calls.Should().ContainSingle(c => c.Lo == "a" && c.Hi == "b"); // the call happened — atomicity unasserted
    }

    // LYING TEST (exhibit, do not copy) — gallery case G-TAUT (the mirror in its purest
    // form); counterpart is the ordinary green DmMessageTests.S_DM_11 (list through real
    // HTTP + real DB). Stub the message source to return a canned message, then assert the
    // service returns that message — the assertion verifies the stub, not the system.
    [Fact]
    public async Task TautologicalLyingTest_asserts_the_canned_message_it_stubbed()
    {
        var canned = new DmMessage { Id = "m1", ConversationId = "c1", SenderId = "a", Text = "hello" };
        var store = new CannedMessageStore(canned);

        var messages = await store.List("c1", CancellationToken.None);

        messages.Single().Text.Should().Be("hello"); // green — it only mirrors the stub
    }

    private sealed class AlwaysGrantsDmAccess(DmConversation conversation) : IDmAccess
    {
        public Task<DmConversation?> GetForParticipant(string conversationId, string userId, CancellationToken ct) =>
            Task.FromResult<DmConversation?>(conversation);
    }

    private sealed class CannedMessageStore(DmMessage canned)
    {
        public Task<IReadOnlyList<DmMessage>> List(string conversationId, CancellationToken ct) =>
            Task.FromResult<IReadOnlyList<DmMessage>>([canned]);
    }

    private sealed class IdempotentFakeWriter(DmConversation conversation) : IConversationWriter
    {
        public Task<ConversationCreateResult> Create(string userLo, string userHi, CancellationToken ct) =>
            Task.FromResult(new ConversationCreateResult(conversation, Created: false));
    }

    private sealed class RecordingConversationWriter : IConversationWriter
    {
        public List<(string Lo, string Hi)> Calls { get; } = [];

        public Task<ConversationCreateResult> Create(string userLo, string userHi, CancellationToken ct)
        {
            Calls.Add((userLo, userHi));
            var conversation = new DmConversation { Id = "c1", UserLo = userLo, UserHi = userHi };
            return Task.FromResult(new ConversationCreateResult(conversation, Created: true));
        }
    }
}
