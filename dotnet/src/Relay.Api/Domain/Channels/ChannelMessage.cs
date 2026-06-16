namespace Relay.Api.Domain.Channels;

public sealed class ChannelMessage
{
    public required string Id { get; init; }
    public required string ChannelId { get; init; }
    public required string SenderId { get; init; }
    public required string Text { get; set; }
    public string? LinkPreviewTitle { get; set; }
    public DateTime CreatedAt { get; init; }
}
