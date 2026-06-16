using System.Net;
using FluentAssertions;
using Relay.Api.Features.Channels;
using Relay.Api.Tests.Infrastructure;
using Relay.Api.Tests.Naive;

namespace Relay.Api.Tests.Features.Channels;

/// <summary>
/// G-CACHE: cache coherence is a property of TWO stores plus the write path — it exists
/// in no unit. The kicked member's next read must be denied immediately, not at TTL.
/// </summary>
[Collection(RelayCollection.Name)]
public sealed class ChannelCacheTests(TestFixture fixture) : RelayTest(fixture)
{
    [Fact]
    public async Task S_CH_16_kick_invalidates_membership_cache_immediately()
    {
        var (channel, bob, owner) = await PrivateChannelWithMember();
        await AssertKickRevokesReadImmediately(
            Fixture.Http.CreateClient(owner.Id), Fixture.Http.CreateClient(bob.Id), channel.Id, bob.Id);
    }

    [Fact]
    public async Task S_CH_16_naive_kick_that_forgets_invalidation_is_caught()
    {
        var (channel, bob, owner) = await PrivateChannelWithMember();
        var naiveKicker = Fixture.NaiveClient<IMembershipWriter, NaiveMembershipWriter>(owner.Id);
        await NaiveDemo.ExpectCatchToFail("G-CACHE", () => AssertKickRevokesReadImmediately(
            naiveKicker, Fixture.Http.CreateClient(bob.Id), channel.Id, bob.Id));
    }

    /// <summary>
    /// The catching assertion block: warm the cache through a real read, kick through the
    /// (correct or naive) write path, then read again IMMEDIATELY. Both the HTTP answer
    /// and the Redis key are asserted — the stale-cache bug leaks through both.
    /// </summary>
    private async Task AssertKickRevokesReadImmediately(
        HttpClient kicker, HttpClient member, string channelId, string memberId)
    {
        var warmRead = await member.GetAsync($"/channels/{channelId}/messages");
        warmRead.StatusCode.Should().Be(HttpStatusCode.OK);
        (await Fixture.Redis.SetMembers($"members:{channelId}")).Should().Contain(memberId);

        var kick = await kicker.DeleteAsync($"/channels/{channelId}/members/{memberId}");
        kick.StatusCode.Should().Be(HttpStatusCode.NoContent);

        var rereadAfterKick = await member.GetAsync($"/channels/{channelId}/messages");
        rereadAfterKick.StatusCode.Should().Be(HttpStatusCode.NotFound);
        (await rereadAfterKick.ReadError()).Code.Should().Be("channel:not_found");

        (await Fixture.Redis.SetMembers($"members:{channelId}")).Should().NotContain(memberId);
    }

    private async Task<(Domain.Channels.Channel Channel, Domain.Users.User Member, Domain.Users.User Owner)>
        PrivateChannelWithMember()
    {
        var owner = await Seed.User("ada");
        var bob = await Seed.User("bob");
        var channel = await Seed.Channel("secret", isPrivate: true, owner);
        await Seed.Member(channel, bob, Domain.Channels.ChannelRole.Member);
        await Seed.ChannelMessage(channel, owner, "for members only");
        return (channel, bob, owner);
    }
}
