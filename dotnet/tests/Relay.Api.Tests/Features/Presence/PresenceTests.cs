using System.Net;
using FluentAssertions;
using Relay.Api.Domain.Channels;
using Relay.Api.Features.Presence;
using Relay.Api.Tests.Infrastructure;
using Relay.Api.Tests.Naive;

namespace Relay.Api.Tests.Features.Presence;

/// <summary>
/// G-GRPC: stream semantics are a transport property — a partial stream presented as
/// complete exists only against a real gRPC service that can fail mid-stream. The catch
/// (S-PR-04): the harness arms "fail after 2 messages"; channel presence must be 502
/// <c>presence:incomplete</c> with NO partial list, never a 2-member 200. The presence
/// service runs as a real in-process gRPC host over a real socket (PresenceHarness), so
/// this also proves the harness boundary is transport-agnostic.
/// </summary>
[Collection(RelayCollection.Name)]
public sealed class PresenceTests(TestFixture fixture) : RelayTest(fixture)
{
    [Fact]
    public async Task S_PR_01_heartbeat_makes_a_user_online_on_the_unary_path()
    {
        var ada = await Seed.User("ada");
        var bob = await Seed.User("bob");

        (await Fixture.Http.CreateClient(bob.Id).PostAsync("/me/heartbeat", null))
            .StatusCode.Should().Be(HttpStatusCode.NoContent);

        var response = await Fixture.Http.CreateClient(ada.Id).GetAsync($"/users/{bob.Id}/presence");
        response.StatusCode.Should().Be(HttpStatusCode.OK);
        (await response.ReadJson<PresenceResponse>()).Status.Should().Be("online");
    }

    [Fact]
    public async Task S_PR_02_a_user_without_a_heartbeat_is_offline()
    {
        var ada = await Seed.User("ada");
        var cleo = await Seed.User("cleo");

        var response = await Fixture.Http.CreateClient(ada.Id).GetAsync($"/users/{cleo.Id}/presence");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        var body = await response.ReadJson<PresenceResponse>();
        body.UserId.Should().Be(cleo.Id);
        body.Status.Should().Be("offline");
    }

    [Fact]
    public async Task S_PR_02_unknown_user_presence_is_404()
    {
        var ada = await Seed.User("ada");

        var response = await Fixture.Http.CreateClient(ada.Id).GetAsync("/users/UNKNOWN0000000/presence");

        response.StatusCode.Should().Be(HttpStatusCode.NotFound);
        (await response.ReadError()).Code.Should().Be("user:not_found");
    }

    [Fact]
    public async Task S_PR_03_channel_presence_streams_every_member_to_completion()
    {
        var (owner, channel, members) = await ChannelOfFive();
        await Fixture.Presence.SetOnline(members[0].Id);
        await Fixture.Presence.SetOnline(members[1].Id);

        var response = await Fixture.Http.CreateClient(owner.Id).GetAsync($"/channels/{channel.Id}/presence");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        var body = await response.ReadJson<ChannelPresenceResponse>();
        body.Members.Should().HaveCount(5);
        body.Members.Where(m => m.Status == "online").Select(m => m.UserId)
            .Should().BeEquivalentTo([members[0].Id, members[1].Id]);
        body.Members.Count(m => m.Status == "offline").Should().Be(3);
    }

    // ---- G-GRPC: a mid-stream failure must be 502, never a partial list ----

    [Fact]
    public async Task S_PR_04_partial_stream_is_502_not_a_partial_list()
    {
        var (owner, channel, _) = await ChannelOfFive();
        await AssertPartialStreamRejected(Fixture.Http.CreateClient(owner.Id), channel.Id);
    }

    [Fact]
    public async Task S_PR_04_naive_swallowing_the_stream_error_is_caught()
    {
        var (owner, channel, _) = await ChannelOfFive();
        var naive = Fixture.NaiveClient<IPresenceClient, NaivePresenceClient>(owner.Id);
        await NaiveDemo.ExpectCatchToFail("G-GRPC", () => AssertPartialStreamRejected(naive, channel.Id));
    }

    private async Task AssertPartialStreamRejected(HttpClient client, string channelId)
    {
        Fixture.Presence.FailStreamAfter(2);

        var response = await client.GetAsync($"/channels/{channelId}/presence");

        response.StatusCode.Should().Be(HttpStatusCode.BadGateway);
        (await response.ReadError()).Code.Should().Be("presence:incomplete");
        (await response.ReadRawBody()).Should().NotContain("\"members\"");
    }

    [Fact]
    public async Task S_PR_05_non_member_channel_presence_is_403_public_and_404_private()
    {
        var owner = await Seed.User("ada");
        var outsider = await Seed.User("cleo");
        var publicChannel = await Seed.Channel("general", isPrivate: false, owner);
        var privateChannel = await Seed.Channel("secret", isPrivate: true, owner);
        var client = Fixture.Http.CreateClient(outsider.Id);

        var publicResponse = await client.GetAsync($"/channels/{publicChannel.Id}/presence");
        publicResponse.StatusCode.Should().Be(HttpStatusCode.Forbidden);
        (await publicResponse.ReadError()).Code.Should().Be("channel:membership_required");

        var privateResponse = await client.GetAsync($"/channels/{privateChannel.Id}/presence");
        privateResponse.StatusCode.Should().Be(HttpStatusCode.NotFound);
        (await privateResponse.ReadError()).Code.Should().Be("channel:not_found");
    }

    private async Task<(Domain.Users.User Owner, Channel Channel, Domain.Users.User[] Members)> ChannelOfFive()
    {
        var owner = await Seed.User("owner");
        var channel = await Seed.Channel("team", isPrivate: false, owner);
        var others = new List<Domain.Users.User> { owner };
        foreach (var handle in new[] { "bob", "cleo", "dan", "eve" })
        {
            var member = await Seed.User(handle);
            await Seed.Member(channel, member, ChannelRole.Member);
            others.Add(member);
        }

        return (owner, channel, [.. others]);
    }

    private sealed record PresenceResponse(string UserId, string Status);

    private sealed record ChannelPresenceResponse(MemberPresenceItem[] Members);

    private sealed record MemberPresenceItem(string UserId, string Status);
}
