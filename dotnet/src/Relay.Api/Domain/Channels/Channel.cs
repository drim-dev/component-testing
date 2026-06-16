namespace Relay.Api.Domain.Channels;

public sealed class Channel
{
    public required string Id { get; init; }
    public required string Name { get; set; }
    public required bool Private { get; set; }
    public DateTime CreatedAt { get; init; }

    public List<ChannelMember> Members { get; } = [];
}
