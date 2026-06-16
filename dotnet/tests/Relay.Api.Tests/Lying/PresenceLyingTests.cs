using FluentAssertions;
using Relay.Api.Features.Presence;

namespace Relay.Api.Tests.Lying;

/// <summary>
/// LYING TESTS (exhibits, do not copy) — see <see cref="MessagesLyingTests"/> for the
/// framing. The gRPC mirror: mock the presence client so it returns a fully-materialized
/// member list. Streaming — and its failure midway — does not exist in the mock's
/// universe, so the bug the catching test exists for (a partial stream surfaced as
/// complete) is unrepresentable here. Green by construction.
/// </summary>
public sealed class PresenceLyingTests
{
    // LYING TEST (exhibit, do not copy) — gallery case G-GRPC;
    // caught by PresenceTests.S_PR_04_partial_stream_is_502_not_a_partial_list
    [Fact]
    public async Task GrpcLyingTest_mocked_client_returns_a_whole_list_so_partial_streams_cannot_exist()
    {
        IPresenceClient client = new FullyMaterializedPresence(
        [
            new MemberPresence("u1", true),
            new MemberPresence("u2", false),
        ]);

        var members = await client.StreamPresence(["u1", "u2"], CancellationToken.None);

        // Green — and just as green against the naive client that swallows a mid-stream
        // error: there is no stream and no transport to fail, so the assertion only mirrors
        // the canned list.
        members.Should().HaveCount(2);
    }

    private sealed class FullyMaterializedPresence(IReadOnlyList<MemberPresence> canned) : IPresenceClient
    {
        public Task<bool> IsOnline(string userId, CancellationToken ct) => Task.FromResult(true);

        public Task<IReadOnlyList<MemberPresence>> StreamPresence(
            IReadOnlyList<string> userIds, CancellationToken ct) => Task.FromResult(canned);
    }
}
