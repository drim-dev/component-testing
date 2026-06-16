namespace Relay.Api.Domain.Messages;

/// <summary>
/// Denormalized membership of a conversation. Exists so DM creation is a multi-row
/// atomic write (conversation + two participants) — the surface the TX case probes.
/// </summary>
public sealed class DmParticipant
{
    public required string ConversationId { get; init; }
    public required string UserId { get; init; }
}
