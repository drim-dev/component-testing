namespace Relay.Api.Domain.Channels;

public sealed class ChannelMember
{
    public required string ChannelId { get; init; }
    public required string UserId { get; init; }
    public required ChannelRole Role { get; set; }
    public DateTime JoinedAt { get; init; }
}
