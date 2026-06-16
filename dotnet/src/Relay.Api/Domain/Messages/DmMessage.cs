namespace Relay.Api.Domain.Messages;

public sealed class DmMessage
{
    public required string Id { get; init; }
    public required string ConversationId { get; init; }
    public required string SenderId { get; init; }
    public required string Text { get; set; }
    public DateTime CreatedAt { get; init; }
}
