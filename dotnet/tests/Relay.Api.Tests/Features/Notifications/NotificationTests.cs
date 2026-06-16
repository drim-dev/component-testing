using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Relay.Api.Domain.Notifications;
using Relay.Api.Features.Notifications;
using Relay.Api.Tests.Infrastructure;
using Relay.Api.Tests.Naive;
using Relay.Testing;

namespace Relay.Api.Tests.Features.Notifications;

/// <summary>
/// G-RABBIT: idempotency under at-least-once delivery exists only with a real broker's
/// redelivery semantics. The catching pin (S-NT-02): a redelivered job converges —
/// exactly one row AND an empty DLQ; the naive insert-or-crash worker dead-letters the
/// healthy duplicate instead.
/// </summary>
[Collection(RelayCollection.Name)]
public sealed class NotificationTests(TestFixture fixture) : RelayTest(fixture)
{
    private static readonly string MainDlq =
        NotificationQueues.DeadLetterQueue(RabbitMqHarness<Program>.Queue);

    private static readonly string NaiveDlq =
        NotificationQueues.DeadLetterQueue(RabbitMqHarness<Program>.NaiveQueue);

    [Fact]
    public async Task S_NT_01_dm_send_yields_exactly_one_notification_for_the_recipient()
    {
        var (ada, bob, conversation) = await ConversationPair();
        var longText = new string('x', 150);

        var sent = await Fixture.Http.CreateClient(ada.Id)
            .PostAsJsonAsync($"/dm/conversations/{conversation.Id}/messages", new { text = longText });
        sent.StatusCode.Should().Be(HttpStatusCode.Created);
        var message = await sent.ReadJson<MessageResponse>();

        await Awaiting.Until(
            async () => await Fixture.Database.Count<Notification>(n => n.DmMessageId == message.Id) == 1,
            "bob's notification row appears");

        var page = await (await Fixture.Http.CreateClient(bob.Id).GetAsync("/notifications"))
            .ReadJson<NotificationPage>();
        var notification = page.Items.Single();
        notification.Type.Should().Be("dm.message");
        notification.DmMessageId.Should().Be(message.Id);
        notification.ConversationId.Should().Be(conversation.Id);
        notification.SenderId.Should().Be(ada.Id);
        notification.Preview.Should().Be(longText[..100]);

        (await Fixture.Database.Count<Notification>(n => n.UserId == ada.Id)).Should().Be(0);
    }

    // ---- G-RABBIT: redelivery of the same job must converge, not dead-letter ----

    [Fact]
    public async Task S_NT_02_redelivered_job_converges_to_one_row_and_empty_dlq()
    {
        var (ada, bob, conversation) = await ConversationPair();
        var sent = await Fixture.Http.CreateClient(ada.Id)
            .PostAsJsonAsync($"/dm/conversations/{conversation.Id}/messages", new { text = "hello bob" });
        var message = await sent.ReadJson<MessageResponse>();
        await Awaiting.Until(
            async () => await Fixture.Database.Count<Notification>(n => n.DmMessageId == message.Id) == 1,
            "the first delivery lands");

        var duplicate = new NotificationJob(message.Id, conversation.Id, ada.Id, bob.Id, "hello bob");
        await AssertRedeliveredJobConverges(duplicate, RabbitMqHarness<Program>.Queue, MainDlq);
    }

    [Fact]
    public async Task S_NT_02_naive_insert_or_crash_worker_is_caught()
    {
        var (ada, bob, conversation) = await ConversationPair();
        var message = await Seed.DmMessage(conversation, ada, "hello bob");
        var job = new NotificationJob(message.Id, conversation.Id, ada.Id, bob.Id, "hello bob");

        await using var naive = Fixture.NaiveWorkers<INotificationRecorder, NaiveNotificationRecorder>();
        await Fixture.Rabbit.Publish(job, RabbitMqHarness<Program>.NaiveQueue);
        await Awaiting.Until(
            async () => await Fixture.Database.Count<Notification>(n => n.DmMessageId == message.Id) == 1,
            "the naive worker lands the first delivery");

        await NaiveDemo.ExpectCatchToFail("G-RABBIT", () => AssertRedeliveredJobConverges(
            job, RabbitMqHarness<Program>.NaiveQueue, NaiveDlq));
    }

    private async Task AssertRedeliveredJobConverges(NotificationJob duplicate, string queue, string dlq)
    {
        await Fixture.Rabbit.Publish(duplicate, queue);
        await Fixture.Rabbit.AwaitSettled(queue, Timeout(30));

        (await Fixture.Database.Count<Notification>(n => n.DmMessageId == duplicate.DmMessageId))
            .Should().Be(1);
        (await Fixture.Rabbit.ReadyCount(dlq))
            .Should().Be(0, "a redelivered duplicate is a healthy event, not poison");
    }

    // ---- poison goes to the DLQ after 3 attempts; the queue keeps flowing ----

    [Fact]
    public async Task S_NT_03_poison_job_lands_in_dlq_after_three_attempts()
    {
        var (ada, _, conversation) = await ConversationPair();
        var message = await Seed.DmMessage(conversation, ada, "poison target");
        var poison = new NotificationJob(message.Id, conversation.Id, ada.Id, "no-such-user", "poison");

        await Fixture.Rabbit.Publish(poison);
        await Fixture.Rabbit.AwaitDepth(MainDlq, 1, Timeout(30));

        await Fixture.Rabbit.AwaitSettled(RabbitMqHarness<Program>.Queue, Timeout(30));
        (await Fixture.Database.Count<Notification>(n => n.DmMessageId == message.Id)).Should().Be(0);
    }

    [Fact]
    public async Task S_NT_04_valid_job_flows_past_a_poison_one()
    {
        var (ada, bob, conversation) = await ConversationPair();
        var poisonTarget = await Seed.DmMessage(conversation, ada, "poison target");
        var validTarget = await Seed.DmMessage(conversation, ada, "valid target");

        await Fixture.Rabbit.Publish(
            new NotificationJob(poisonTarget.Id, conversation.Id, ada.Id, "no-such-user", "poison"));
        await Fixture.Rabbit.Publish(
            new NotificationJob(validTarget.Id, conversation.Id, ada.Id, bob.Id, "valid"));

        await Awaiting.Until(
            async () => await Fixture.Database.Count<Notification>(n => n.DmMessageId == validTarget.Id) == 1,
            "the valid job is processed despite the poison one");
        await Fixture.Rabbit.AwaitSettled(RabbitMqHarness<Program>.Queue, Timeout(30));
        await Fixture.Rabbit.AwaitDepth(MainDlq, 1, Timeout(30));
    }

    [Fact]
    public async Task S_NT_05_notifications_list_is_mine_only_newest_first()
    {
        var ada = await Seed.User("ada");
        var bob = await Seed.User("bob");
        var cleo = await Seed.User("cleo");
        var adaBob = await Seed.Conversation(ada, bob);
        var adaCleo = await Seed.Conversation(ada, cleo);

        var first = await Seed.DmMessage(adaBob, ada, "to bob, first");
        var second = await Seed.DmMessage(adaBob, ada, "to bob, second");
        var other = await Seed.DmMessage(adaCleo, ada, "to cleo");
        await Seed.Notification(bob, first);
        await Seed.Notification(bob, second);
        await Seed.Notification(cleo, other);

        var page = await (await Fixture.Http.CreateClient(bob.Id).GetAsync("/notifications"))
            .ReadJson<NotificationPage>();

        page.Items.Select(n => n.Preview).Should().Equal("to bob, second", "to bob, first");
        page.Items.Should().OnlyContain(n => n.SenderId == ada.Id);
    }

    private async Task<(Domain.Users.User Ada, Domain.Users.User Bob, Domain.Messages.DmConversation Conversation)>
        ConversationPair()
    {
        var ada = await Seed.User("ada");
        var bob = await Seed.User("bob");
        var conversation = await Seed.Conversation(ada, bob);
        return (ada, bob, conversation);
    }

    private sealed record MessageResponse(string Id, string ConversationId, string SenderId, string Text, DateTime CreatedAt);

    private sealed record NotificationPage(NotificationItem[] Items, string? NextBefore);

    private sealed record NotificationItem(
        string Id,
        string Type,
        string DmMessageId,
        string ConversationId,
        string SenderId,
        string Preview,
        DateTime CreatedAt);
}
