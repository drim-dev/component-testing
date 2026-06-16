namespace Relay.Api.Domain.Messages;

/// <summary>
/// A 1-to-1 conversation. The pair is stored normalized (<c>UserLo &lt; UserHi</c>) and is
/// unique — at most one conversation per pair, the invariant the RACE case leans on.
/// </summary>
public sealed class DmConversation
{
    public required string Id { get; init; }
    public required string UserLo { get; init; }
    public required string UserHi { get; init; }
    public DateTime CreatedAt { get; init; }

    public List<DmParticipant> Participants { get; } = [];
}
