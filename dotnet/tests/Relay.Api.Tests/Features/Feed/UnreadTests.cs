using System.Net;
using FluentAssertions;
using Relay.Api.Tests.Infrastructure;

namespace Relay.Api.Tests.Features.Feed;

[Collection(RelayCollection.Name)]
public sealed class UnreadTests(TestFixture fixture) : RelayTest(fixture)
{
    [Fact]
    public async Task S_FD_04_read_resets_one_channel_and_leaves_others_untouched()
    {
        var owner = await Seed.User("ada");
        var bob = await Seed.User("bob");
        var first = await Seed.Channel("first", isPrivate: false, owner);
        var second = await Seed.Channel("second", isPrivate: false, owner);
        await Seed.Member(first, bob, Domain.Channels.ChannelRole.Member);
        await Seed.Member(second, bob, Domain.Channels.ChannelRole.Member);
        await Fixture.Redis.SetCounter($"unread:{bob.Id}:{first.Id}", 3);
        await Fixture.Redis.SetCounter($"unread:{bob.Id}:{second.Id}", 2);

        var client = Fixture.Http.CreateClient(bob.Id);
        var response = await client.PostAsync($"/channels/{first.Id}/read", content: null);

        response.StatusCode.Should().Be(HttpStatusCode.NoContent);
        var unread = await (await client.GetAsync("/me/unread")).ReadJson<UnreadResponse>();
        unread.Channels.Should().NotContainKey(first.Id);
        unread.Channels.Should().Contain(second.Id, 2);
    }

    private sealed record UnreadResponse(Dictionary<string, long> Channels);
}
