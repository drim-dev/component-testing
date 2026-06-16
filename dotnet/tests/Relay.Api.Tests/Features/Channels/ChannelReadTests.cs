using System.Net;
using FluentAssertions;
using Relay.Api.Features.Channels;
using Relay.Api.Tests.Infrastructure;
using Relay.Api.Tests.Naive;

namespace Relay.Api.Tests.Features.Channels;

[Collection(RelayCollection.Name)]
public sealed class ChannelReadTests(TestFixture fixture) : RelayTest(fixture)
{
    [Fact]
    public async Task S_CH_03_list_returns_public_and_own_private_only()
    {
        var ada = await Seed.User("ada");
        var bob = await Seed.User("bob");
        var adaPrivate = await Seed.Channel("ada-secret", isPrivate: true, ada);
        var bobPublic = await Seed.Channel("bob-public", isPrivate: false, bob);
        var bobPrivate = await Seed.Channel("bob-secret", isPrivate: true, bob);

        var response = await Fixture.Http.CreateClient(ada.Id).GetAsync("/channels");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        var page = await response.ReadJson<Page>();
        var ids = page.Items.Select(c => c.Id).ToArray();
        ids.Should().Contain([adaPrivate.Id, bobPublic.Id]);
        ids.Should().NotContain(bobPrivate.Id);
    }

    [Fact]
    public async Task S_CH_04_non_member_reads_public_metadata()
    {
        var ada = await Seed.User("ada");
        var bob = await Seed.User("bob");
        var channel = await Seed.Channel("general", isPrivate: false, bob);

        var response = await Fixture.Http.CreateClient(ada.Id).GetAsync($"/channels/{channel.Id}");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        (await response.ReadJson<ChannelMetadata>()).MemberCount.Should().Be(1);
    }

    // ---- G-BOLA-READ: private channels are invisible to non-members ----

    private static async Task AssertPrivateMetadataHidden(HttpClient nonMember, string channelId)
    {
        var visible = await nonMember.GetAsync($"/channels/{channelId}");
        visible.StatusCode.Should().Be(HttpStatusCode.NotFound);
        (await visible.ReadError()).Code.Should().Be("channel:not_found");

        var unknown = await nonMember.GetAsync("/channels/UNKNOWN0000000");
        (await visible.ReadRawBody()).Should().Be(await unknown.ReadRawBody());
    }

    private static async Task AssertPrivateMessagesHidden(HttpClient nonMember, string channelId)
    {
        var response = await nonMember.GetAsync($"/channels/{channelId}/messages");
        response.StatusCode.Should().Be(HttpStatusCode.NotFound);
        (await response.ReadError()).Code.Should().Be("channel:not_found");
    }

    [Fact]
    public async Task S_CH_05_non_member_private_metadata_is_404()
    {
        var (channel, outsider) = await PrivateChannelWithOutsider();
        await AssertPrivateMetadataHidden(Fixture.Http.CreateClient(outsider.Id), channel.Id);
    }

    [Fact]
    public async Task S_CH_05_naive_read_ignoring_private_is_caught()
    {
        var (channel, outsider) = await PrivateChannelWithOutsider();
        var naive = Fixture.NaiveClient<IChannelReadGate, NaiveChannelReadGate>(outsider.Id);
        await NaiveDemo.ExpectCatchToFail("G-BOLA-READ", () => AssertPrivateMetadataHidden(naive, channel.Id));
    }

    [Fact]
    public async Task S_CH_21_non_member_private_messages_is_404()
    {
        var (channel, outsider) = await PrivateChannelWithOutsider(seedMessages: true);
        await AssertPrivateMessagesHidden(Fixture.Http.CreateClient(outsider.Id), channel.Id);
    }

    [Fact]
    public async Task S_CH_21_naive_message_read_ignoring_private_is_caught()
    {
        var (channel, outsider) = await PrivateChannelWithOutsider(seedMessages: true);
        var naive = Fixture.NaiveClient<IChannelReadGate, NaiveChannelReadGate>(outsider.Id);
        await NaiveDemo.ExpectCatchToFail("G-BOLA-READ", () => AssertPrivateMessagesHidden(naive, channel.Id));
    }

    [Fact]
    public async Task S_CH_22_non_member_public_messages_is_403()
    {
        var ada = await Seed.User("ada");
        var bob = await Seed.User("bob");
        var channel = await Seed.Channel("general", isPrivate: false, bob);

        var response = await Fixture.Http.CreateClient(ada.Id).GetAsync($"/channels/{channel.Id}/messages");

        response.StatusCode.Should().Be(HttpStatusCode.Forbidden);
        (await response.ReadError()).Code.Should().Be("channel:membership_required");
    }

    // ---- G-WEAKVAL: strict pagination bound (1..100), 422 not silent clamp ----

    [Theory]
    [InlineData("0", "pagination:limit:out_of_range")]
    [InlineData("101", "pagination:limit:out_of_range")]
    [InlineData("-5", "pagination:limit:out_of_range")]
    [InlineData("abc", "pagination:limit:not_a_number")]
    public async Task S_PG_01_02_03_limit_out_of_bounds_is_422(string limit, string code)
    {
        var (owner, channel) = await OwnedChannel();
        var response = await Fixture.Http.CreateClient(owner.Id)
            .GetAsync($"/channels/{channel.Id}/messages?limit={limit}");

        response.StatusCode.Should().Be(HttpStatusCode.UnprocessableEntity);
        (await response.ReadError()).Code.Should().Be(code);
    }

    [Fact]
    public async Task S_PG_04_unknown_before_cursor_is_422()
    {
        var (owner, channel) = await OwnedChannel();
        var response = await Fixture.Http.CreateClient(owner.Id)
            .GetAsync($"/channels/{channel.Id}/messages?before=NEVER0000RETURNED");

        response.StatusCode.Should().Be(HttpStatusCode.UnprocessableEntity);
        (await response.ReadError()).Code.Should().Be("pagination:before:unknown");
    }

    [Fact]
    public async Task S_PG_05_default_page_then_cursor_walks_to_end()
    {
        var (owner, channel) = await OwnedChannel();
        for (var i = 0; i < 60; i++)
        {
            await Seed.ChannelMessage(channel, owner, $"message {i}");
        }

        var client = Fixture.Http.CreateClient(owner.Id);
        var first = await (await client.GetAsync($"/channels/{channel.Id}/messages")).ReadJson<MessagePage>();
        first.Items.Should().HaveCount(50);
        first.NextBefore.Should().NotBeNull();

        var second = await (await client.GetAsync($"/channels/{channel.Id}/messages?before={first.NextBefore}"))
            .ReadJson<MessagePage>();
        second.Items.Should().HaveCount(10);
        second.NextBefore.Should().BeNull();
    }

    private async Task<(Domain.Users.User Owner, Domain.Channels.Channel Channel)> OwnedChannel()
    {
        var owner = await Seed.User("ada");
        var channel = await Seed.Channel("general", isPrivate: false, owner);
        return (owner, channel);
    }

    private async Task<(Domain.Channels.Channel Channel, Domain.Users.User Outsider)> PrivateChannelWithOutsider(
        bool seedMessages = false)
    {
        var owner = await Seed.User("ada");
        var outsider = await Seed.User("cleo");
        var channel = await Seed.Channel("secret", isPrivate: true, owner);
        if (seedMessages)
        {
            await Seed.ChannelMessage(channel, owner, "secret message");
        }

        return (channel, outsider);
    }

    private sealed record Page(ChannelMetadata[] Items, string? NextBefore);

    private sealed record ChannelMetadata(string Id, string Name, bool Private, int MemberCount, DateTime CreatedAt);

    private sealed record MessagePage(MessageItem[] Items, string? NextBefore);

    private sealed record MessageItem(string Id, string ChannelId, string SenderId, string Text, string? LinkPreviewTitle, DateTime CreatedAt);
}
