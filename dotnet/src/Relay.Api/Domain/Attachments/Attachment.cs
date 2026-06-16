namespace Relay.Api.Domain.Attachments;

public sealed class Attachment
{
    public required string Id { get; init; }
    public required string ChannelId { get; init; }
    public required string UploaderId { get; init; }
    public string? MessageId { get; set; }
    public required string Filename { get; init; }
    public required long SizeBytes { get; init; }
    public required string StorageKey { get; init; }
    public DateTime CreatedAt { get; init; }
}
