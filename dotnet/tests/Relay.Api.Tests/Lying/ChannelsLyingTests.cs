using FluentAssertions;
using Relay.Api.Common.Pagination;
using Relay.Api.Domain.Channels;
using Relay.Api.Features.Channels;

namespace Relay.Api.Tests.Lying;

/// <summary>
/// LYING TESTS (exhibits, do not copy) for the channel gallery cases — green-by-
/// construction, paired with their catching tests (spec/05-gallery.md §0.2).
/// </summary>
public sealed class ChannelsLyingTests
{
    // LYING TEST (exhibit, do not copy) — gallery case G-BOLA-READ;
    // caught by ChannelReadTests.S_CH_05_non_member_private_metadata_is_404
    [Fact]
    public async Task BolaReadLyingTest_stub_returns_channel_regardless_of_membership()
    {
        var channel = new Channel { Id = "ch1", Name = "secret", Private = true };
        IChannelReadGate gate = new AlwaysGrantsReadGate(channel);

        var returned = await gate.AuthorizeMetadata("ch1", "outsider", CancellationToken.None);

        returned.Should().BeSameAs(channel); // private/membership never consulted — the stub just hands it back
    }

    // LYING TEST (exhibit, do not copy) — gallery case G-BOLA-ROLE;
    // caught by ChannelMembershipTests.S_CH_15_member_kick_is_403
    [Fact]
    public void BolaRoleLyingTest_builds_the_authority_it_should_verify()
    {
        var fabricated = new ChannelMember { ChannelId = "ch1", UserId = "member", Role = ChannelRole.Admin };

        ChannelRoles.AtLeast(fabricated.Role, ChannelRole.Admin).Should().BeTrue(); // green — but the route never built this
    }

    // LYING TEST (exhibit, do not copy) — gallery case G-WEAKVAL;
    // caught by ChannelReadTests.S_PG_01_02_03_limit_out_of_bounds_is_422
    [Fact]
    public void WeakValLyingTest_asserts_only_the_happy_value()
    {
        Paging.ParseLimit("50").Should().Be(50); // "pagination works" — the 0/101/abc/before bounds are never asserted
    }

    private sealed class AlwaysGrantsReadGate(Channel channel) : IChannelReadGate
    {
        public Task<Channel> AuthorizeMetadata(string channelId, string userId, CancellationToken ct) =>
            Task.FromResult(channel);

        public Task<Channel> AuthorizeMessageRead(string channelId, string userId, CancellationToken ct) =>
            Task.FromResult(channel);
    }
}
