namespace Relay.Api.Domain.Notifications;

/// <summary>
/// One row per DM message delivered. <c>DmMessageId</c> is UNIQUE — the idempotency
/// anchor the RabbitMQ worker relies on under at-least-once redelivery.
/// </summary>
public sealed class Notification
{
    public required string Id { get; init; }
    public required string UserId { get; init; }
    public required string DmMessageId { get; init; }
    public required string ConversationId { get; init; }
    public required string SenderId { get; init; }
    public required string Preview { get; init; }
    public DateTime CreatedAt { get; init; }
}
