using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Relay.Api.Domain.Channels;
using Relay.Api.Domain.Feed;
using Relay.Api.Features.Channels;
using Relay.Api.Features.Feed;
using Relay.Api.Tests.Infrastructure;
using Relay.Api.Tests.Naive;
using Relay.Testing;

namespace Relay.Api.Tests.Features.Feed;

/// <summary>
/// G-KAFKA: delivery semantics exist only against a real broker. The producer pin
/// (S-FD-01: confirmed publish or 503, never silent loss) and the consumer pin
/// (S-FD-05: feed and counter converged under redelivery) each carry a naive red→green
/// demonstration; S-FD-02 is the await-shape exhibit the guide's §7 dissects.
/// </summary>
[Collection(RelayCollection.Name)]
public sealed class FeedFanoutTests(TestFixture fixture) : RelayTest(fixture)
{
    // ---- G-KAFKA, producer shape: broker down must fail closed ----

    [Fact]
    public async Task S_FD_01_broker_down_fails_closed_with_503()
    {
        var (owner, channel) = await ChannelWithOwner();
        await Fixture.Kafka.StopBroker(Timeout(60));
        try
        {
            await AssertBrokerDownFailsClosed(Fixture.Http.CreateClient(owner.Id), channel.Id);
        }
        finally
        {
            await Fixture.Kafka.StartBroker(Timeout(120));
        }
    }

    [Fact]
    public async Task S_FD_01_naive_fire_and_forget_publish_is_caught()
    {
        var (owner, channel) = await ChannelWithOwner();
        // The naive host publishes to the parallel naive topic so its buffered
        // fire-and-forget messages can never leak into the suite's real event stream.
        var naive = Fixture.NaiveClient<IMessagePostedPublisher, NaiveMessagePostedPublisher>(
            owner.Id, ("Kafka:Topic", KafkaHarness<Program>.NaiveTopic));

        await Fixture.Kafka.StopBroker(Timeout(60));
        try
        {
            await NaiveDemo.ExpectCatchToFail("G-KAFKA", () => AssertBrokerDownFailsClosed(naive, channel.Id));
        }
        finally
        {
            await Fixture.Kafka.StartBroker(Timeout(120));
        }
    }

    private async Task AssertBrokerDownFailsClosed(HttpClient poster, string channelId)
    {
        var response = await poster.PostAsJsonAsync(
            $"/channels/{channelId}/messages", new { text = "posted while the broker is down" });

        response.StatusCode.Should().Be(HttpStatusCode.ServiceUnavailable);
        (await response.ReadError()).Code.Should().Be("events:unavailable");
        (await Fixture.Database.Count<ChannelMessage>(m => m.ChannelId == channelId)).Should().Be(0);
    }

    // ---- the await-shape exhibit: eventual consistency asserted with a deadline ----

    [Fact]
    public async Task S_FD_02_post_fans_out_to_members_except_sender()
    {
        var (ada, bob, cleo, channel) = await ChannelWithThreeMembers();
        var posted = await Post(ada.Id, channel.Id, "fan this out");

        var bobClient = Fixture.Http.CreateClient(bob.Id);
        var cleoClient = Fixture.Http.CreateClient(cleo.Id);
        await Awaiting.Until(
            async () => (await Feed(bobClient)).Items.Count == 1, "bob's feed entry appears");
        await Awaiting.Until(
            async () => (await Feed(cleoClient)).Items.Count == 1, "cleo's feed entry appears");

        var entry = (await Feed(bobClient)).Items.Single();
        entry.ChannelId.Should().Be(channel.Id);
        entry.MessageId.Should().Be(posted.Id);
        entry.SenderId.Should().Be(ada.Id);
        entry.Preview.Should().Be("fan this out");

        (await Feed(Fixture.Http.CreateClient(ada.Id))).Items.Should().BeEmpty();
    }

    [Fact]
    public async Task S_FD_03_unread_counts_accumulate_per_posted_message()
    {
        var (ada, bob, _, channel) = await ChannelWithThreeMembers();
        var bobClient = Fixture.Http.CreateClient(bob.Id);

        await Post(ada.Id, channel.Id, "first");
        await Awaiting.Until(
            async () => (await Unread(bobClient)).GetValueOrDefault(channel.Id) == 1, "bob's unread reaches 1");

        await Post(ada.Id, channel.Id, "second");
        await Awaiting.Until(
            async () => (await Unread(bobClient)).GetValueOrDefault(channel.Id) == 2, "bob's unread reaches 2");
    }

    // ---- G-KAFKA, consumer shape: redelivery must not diverge feed and counter ----

    [Fact]
    public async Task S_FD_05_event_redelivery_keeps_feed_and_counter_converged()
    {
        var (ada, bob, channel) = await ChannelWithMember();
        var posted = await Post(ada.Id, channel.Id, "delivered once");
        await Awaiting.Until(
            async () => await FeedCount(bob.Id, posted.Id) == 1, "bob's feed entry appears");

        var replayed = new MessagePostedEvent(posted.Id, channel.Id, ada.Id, "delivered once", posted.CreatedAt);
        await AssertRedeliveryConverged(
            replayed, bob.Id, KafkaHarness<Program>.Topic, KafkaHarness<Program>.GroupId);
    }

    [Fact]
    public async Task S_FD_05_naive_non_idempotent_consumer_is_caught()
    {
        var (ada, bob, channel) = await ChannelWithMember();
        var crafted = new MessagePostedEvent(
            Fixture.Ids.Create(), channel.Id, ada.Id, "crafted event", DateTime.UtcNow);

        // A naive host whose WORKERS run, wired to the parallel naive topic/group so the
        // suite's correct consumer never races this demo (spec/05-gallery.md §0.4).
        await using var naive = Fixture.NaiveWorkers<IFeedProjector, NaiveFeedProjector>(
            ("Kafka:Topic", KafkaHarness<Program>.NaiveTopic),
            ("Kafka:GroupId", KafkaHarness<Program>.NaiveGroupId));

        await Fixture.Kafka.Publish(crafted, KafkaHarness<Program>.NaiveTopic);
        await Fixture.Kafka.AwaitConsumed(
            Timeout(30), KafkaHarness<Program>.NaiveTopic, KafkaHarness<Program>.NaiveGroupId);

        await NaiveDemo.ExpectCatchToFail("G-KAFKA", () => AssertRedeliveryConverged(
            crafted, bob.Id, KafkaHarness<Program>.NaiveTopic, KafkaHarness<Program>.NaiveGroupId));
    }

    private async Task AssertRedeliveryConverged(
        MessagePostedEvent replayed, string userId, string topic, string groupId)
    {
        await Fixture.Kafka.Publish(replayed, topic);
        await Fixture.Kafka.AwaitConsumed(Timeout(30), topic, groupId);

        (await FeedCount(userId, replayed.MessageId)).Should().Be(1);
        (await Fixture.Redis.GetCounter($"unread:{userId}:{replayed.ChannelId}")).Should().Be(1);
    }

    // ---- G-CACHE companion: a kicked member is out of the fan-out immediately ----

    [Fact]
    public async Task S_FD_06_kicked_member_gets_no_feed_entry_and_no_counter()
    {
        var (ada, bob, cleo, channel) = await ChannelWithThreeMembers();
        var kick = await Fixture.Http.CreateClient(ada.Id)
            .DeleteAsync($"/channels/{channel.Id}/members/{bob.Id}");
        kick.StatusCode.Should().Be(HttpStatusCode.NoContent);

        var posted = await Post(ada.Id, channel.Id, "after the kick");
        await Awaiting.Until(
            async () => await FeedCount(cleo.Id, posted.Id) == 1, "cleo's feed entry appears");
        await Fixture.Kafka.AwaitConsumed(Timeout(30));

        (await FeedCount(bob.Id, posted.Id)).Should().Be(0);
        (await Fixture.Redis.GetCounter($"unread:{bob.Id}:{channel.Id}")).Should().BeNull();
    }

    // ---- helpers ----

    private async Task<MessageResponse> Post(string userId, string channelId, string text)
    {
        var response = await Fixture.Http.CreateClient(userId)
            .PostAsJsonAsync($"/channels/{channelId}/messages", new { text });
        response.StatusCode.Should().Be(HttpStatusCode.Created);
        return await response.ReadJson<MessageResponse>();
    }

    private static async Task<FeedPage> Feed(HttpClient client) =>
        await (await client.GetAsync("/feed")).ReadJson<FeedPage>();

    private static async Task<Dictionary<string, long>> Unread(HttpClient client) =>
        (await (await client.GetAsync("/me/unread")).ReadJson<UnreadResponse>()).Channels;

    private Task<int> FeedCount(string userId, string messageId) =>
        Fixture.Database.Count<FeedEntry>(f => f.UserId == userId && f.MessageId == messageId);

    private async Task<(Domain.Users.User Owner, Domain.Channels.Channel Channel)> ChannelWithOwner()
    {
        var owner = await Seed.User("ada");
        var channel = await Seed.Channel("general", isPrivate: false, owner);
        return (owner, channel);
    }

    private async Task<(Domain.Users.User Owner, Domain.Users.User Member, Domain.Channels.Channel Channel)>
        ChannelWithMember()
    {
        var (owner, channel) = await ChannelWithOwner();
        var bob = await Seed.User("bob");
        await Seed.Member(channel, bob, ChannelRole.Member);
        return (owner, bob, channel);
    }

    private async Task<(Domain.Users.User Owner, Domain.Users.User MemberB, Domain.Users.User MemberC,
        Domain.Channels.Channel Channel)> ChannelWithThreeMembers()
    {
        var (owner, bob, channel) = await ChannelWithMember();
        var cleo = await Seed.User("cleo");
        await Seed.Member(channel, cleo, ChannelRole.Member);
        return (owner, bob, cleo, channel);
    }

    private sealed record MessageResponse(string Id, string ChannelId, string SenderId, string Text, DateTime CreatedAt);

    private sealed record FeedPage(List<FeedItem> Items, string? NextBefore);

    private sealed record FeedItem(string ChannelId, string MessageId, string SenderId, string Preview, DateTime CreatedAt);

    private sealed record UnreadResponse(Dictionary<string, long> Channels);
}
