using Microsoft.EntityFrameworkCore;
using Relay.Api.Database;
using Relay.Api.Domain.Messages;

namespace Relay.Api.Features.Messages;

/// <summary>
/// The participant predicate — pure logic, correct, and (per the gallery) the exact
/// thing a unit test legitimately covers. The bug the gallery exhibits is not this
/// predicate being wrong; it is a read path that never calls it.
/// </summary>
public static class DmParticipation
{
    public static bool IsParticipant(DmConversation conversation, string userId) =>
        conversation.UserLo == userId || conversation.UserHi == userId;
}

/// <summary>
/// DM read authorization (the G-IDOR seam). The correct implementation applies the
/// participant predicate; the route 404s when this returns null, hiding existence.
/// </summary>
public interface IDmAccess
{
    Task<DmConversation?> GetForParticipant(string conversationId, string userId, CancellationToken ct);
}

public sealed class DmAccess(AppDbContext db) : IDmAccess
{
    public async Task<DmConversation?> GetForParticipant(string conversationId, string userId, CancellationToken ct)
    {
        var conversation = await db.DmConversations.AsNoTracking()
            .FirstOrDefaultAsync(c => c.Id == conversationId, ct);

        if (conversation is null || !DmParticipation.IsParticipant(conversation, userId))
        {
            return null;
        }

        return conversation;
    }
}
