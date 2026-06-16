using Relay.Api.Common.Identity;
using Relay.Api.Database;
using Relay.Api.Domain.Notifications;
using Relay.Api.Features.Notifications;

namespace Relay.Api.Tests.Naive;

/// <summary>
/// G-RABBIT naive variant (insert-or-crash): the worker inserts unconditionally and never
/// treats the duplicate as success. Under at-least-once redelivery the second insert hits
/// UNIQUE(dm_message_id), the worker crashes and nack-requeues, and the duplicate job
/// dead-letters after the delivery limit — a healthy redelivery turned into DLQ noise.
/// </summary>
public sealed class NaiveNotificationRecorder(AppDbContext db, IdFactory ids) : INotificationRecorder
{
    public async Task Record(NotificationJob job, CancellationToken ct)
    {
        db.Notifications.Add(new Notification
        {
            Id = ids.Create(),
            UserId = job.RecipientId,
            DmMessageId = job.DmMessageId,
            ConversationId = job.ConversationId,
            SenderId = job.SenderId,
            Preview = job.Preview,
            CreatedAt = DateTime.UtcNow,
        });

        await db.SaveChangesAsync(ct);
    }
}
