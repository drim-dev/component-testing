namespace Relay.Api.Domain.Feed;

/// <summary>
/// A projection row written by the Kafka fan-out consumer. UNIQUE (UserId, MessageId)
/// is the consumer's idempotency backstop; <c>MessageId</c> deliberately carries NO FK
/// (the publish-confirmed-then-commit window means the projection may briefly lead the
/// source table — spec/03-schema.md).
/// </summary>
public sealed class FeedEntry
{
    public required string Id { get; init; }
    public required string UserId { get; init; }
    public required string ChannelId { get; init; }
    public required string MessageId { get; init; }
    public required string SenderId { get; init; }
    public required string Preview { get; init; }
    public DateTime CreatedAt { get; init; }
}
