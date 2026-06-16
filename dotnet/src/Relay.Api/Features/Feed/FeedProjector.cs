using Microsoft.EntityFrameworkCore;
using Npgsql;
using Relay.Api.Common.Identity;
using Relay.Api.Database;
using Relay.Api.Domain.Feed;
using Relay.Api.Features.Channels;

namespace Relay.Api.Features.Feed;

/// <summary>
/// The G-KAFKA consumer seam: applies one <c>message.posted</c> event to the feed
/// projection. Kafka is at-least-once, so the correct implementation is idempotent —
/// the UNIQUE (user_id, message_id) constraint is the backstop, and the unread counter
/// is incremented ONLY on the first successful insert, so feed and counter cannot
/// diverge under event redelivery. The naive variant inserts and increments
/// unconditionally: the duplicate insert is swallowed but the counter runs ahead.
/// </summary>
public interface IFeedProjector
{
    Task Apply(MessagePostedEvent message, CancellationToken ct);
}

public sealed class FeedProjector(AppDbContext db, UnreadCounters counters, IdFactory ids) : IFeedProjector
{
    public async Task Apply(MessagePostedEvent message, CancellationToken ct)
    {
        var memberIds = await db.ChannelMembers.AsNoTracking()
            .Where(m => m.ChannelId == message.ChannelId && m.UserId != message.SenderId)
            .Select(m => m.UserId)
            .ToListAsync(ct);

        foreach (var memberId in memberIds)
        {
            var inserted = await TryInsertFeedEntry(memberId, message, ct);
            if (inserted)
            {
                await counters.Increment(memberId, message.ChannelId);
            }
        }
    }

    private async Task<bool> TryInsertFeedEntry(string userId, MessagePostedEvent message, CancellationToken ct)
    {
        db.FeedEntries.Add(new FeedEntry
        {
            Id = ids.Create(),
            UserId = userId,
            ChannelId = message.ChannelId,
            MessageId = message.MessageId,
            SenderId = message.SenderId,
            Preview = message.Preview,
            CreatedAt = message.PostedAt,
        });

        try
        {
            await db.SaveChangesAsync(ct);
            return true;
        }
        catch (DbUpdateException ex) when (IsUniqueViolation(ex))
        {
            db.ChangeTracker.Clear();
            return false;
        }
    }

    private static bool IsUniqueViolation(DbUpdateException ex) =>
        ex.InnerException is PostgresException { SqlState: PostgresErrorCodes.UniqueViolation };
}
