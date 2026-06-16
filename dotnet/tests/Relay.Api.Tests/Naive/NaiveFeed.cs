using Confluent.Kafka;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Identity;
using Relay.Api.Database;
using Relay.Api.Domain.Feed;
using Relay.Api.Features.Channels;
using Relay.Api.Features.Feed;

namespace Relay.Api.Tests.Naive;

/// <summary>
/// G-KAFKA naive variant, producer shape: fire-and-forget. The publish result is never
/// awaited, so a dead broker still yields 201 — the message persists, the event is
/// silently lost, and the feeds never update.
/// </summary>
public sealed class NaiveMessagePostedPublisher(
    IProducer<string, string> producer,
    Microsoft.Extensions.Configuration.IConfiguration configuration) : IMessagePostedPublisher
{
    private readonly string _topic = configuration.GetMessagePostedTopic();

    public Task Publish(MessagePostedEvent message, CancellationToken ct)
    {
        producer.Produce(_topic, KafkaEvents.Serialize(message));
        return Task.CompletedTask;
    }
}

/// <summary>
/// G-KAFKA naive variant, consumer shape: non-idempotent fan-out. The counter is
/// incremented unconditionally and the duplicate insert error is swallowed — under
/// at-least-once redelivery the unread counter runs ahead of the feed.
/// </summary>
public sealed class NaiveFeedProjector(AppDbContext db, UnreadCounters counters, IdFactory ids) : IFeedProjector
{
    public async Task Apply(MessagePostedEvent message, CancellationToken ct)
    {
        var memberIds = await db.ChannelMembers.AsNoTracking()
            .Where(m => m.ChannelId == message.ChannelId && m.UserId != message.SenderId)
            .Select(m => m.UserId)
            .ToListAsync(ct);

        foreach (var memberId in memberIds)
        {
            await counters.Increment(memberId, message.ChannelId);

            db.FeedEntries.Add(new FeedEntry
            {
                Id = ids.Create(),
                UserId = memberId,
                ChannelId = message.ChannelId,
                MessageId = message.MessageId,
                SenderId = message.SenderId,
                Preview = message.Preview,
                CreatedAt = message.PostedAt,
            });

            try
            {
                await db.SaveChangesAsync(ct);
            }
            catch (DbUpdateException)
            {
                db.ChangeTracker.Clear();
            }
        }
    }
}
