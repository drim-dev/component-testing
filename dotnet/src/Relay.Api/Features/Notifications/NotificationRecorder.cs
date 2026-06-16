using Microsoft.EntityFrameworkCore;
using Npgsql;
using Relay.Api.Common.Identity;
using Relay.Api.Database;
using Relay.Api.Domain.Notifications;

namespace Relay.Api.Features.Notifications;

/// <summary>
/// The G-RABBIT seam: applies one notification job. RabbitMQ redelivers (at-least-once),
/// so the correct implementation treats the duplicate — the UNIQUE(dm_message_id)
/// violation — as SUCCESS, letting the worker ack instead of crash. Any other failure
/// (e.g. a poison job's unresolvable recipient → FK violation) bubbles up: the worker
/// nack-requeues it, and the delivery limit dead-letters it after 3 attempts. The naive
/// variant never handles the duplicate, so a redelivered job crash-loops into the DLQ.
/// </summary>
public interface INotificationRecorder
{
    Task Record(NotificationJob job, CancellationToken ct);
}

public sealed class NotificationRecorder(AppDbContext db, IdFactory ids) : INotificationRecorder
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

        try
        {
            await db.SaveChangesAsync(ct);
        }
        catch (DbUpdateException ex) when (IsUniqueViolation(ex))
        {
            // Redelivered duplicate — the notification already exists. Success.
            db.ChangeTracker.Clear();
        }
    }

    private static bool IsUniqueViolation(DbUpdateException ex) =>
        ex.InnerException is PostgresException { SqlState: PostgresErrorCodes.UniqueViolation };
}
