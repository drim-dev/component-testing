using Microsoft.EntityFrameworkCore;
using Npgsql;
using Relay.Api.Common.Identity;
using Relay.Api.Database;
using Relay.Api.Domain.Messages;

namespace Relay.Api.Features.Messages;

public sealed record ConversationCreateResult(DmConversation Conversation, bool Created);

/// <summary>
/// DM conversation creation — the seam shared by the G-RACE and G-TX cases.
/// The correct implementation writes the conversation and both participants in ONE
/// transaction (atomicity → G-TX) and resolves a concurrent/duplicate create by
/// catching the unique-pair violation and returning the existing row (idempotency +
/// race safety → G-RACE).
/// </summary>
public interface IConversationWriter
{
    Task<ConversationCreateResult> Create(string userLo, string userHi, CancellationToken ct);
}

public sealed class ConversationWriter(AppDbContext db, IdFactory ids) : IConversationWriter
{
    public async Task<ConversationCreateResult> Create(string userLo, string userHi, CancellationToken ct)
    {
        await using var transaction = await db.Database.BeginTransactionAsync(ct);
        try
        {
            var conversation = new DmConversation
            {
                Id = ids.Create(),
                UserLo = userLo,
                UserHi = userHi,
                CreatedAt = DateTime.UtcNow,
            };
            db.DmConversations.Add(conversation);
            db.DmParticipants.Add(new DmParticipant { ConversationId = conversation.Id, UserId = userLo });
            db.DmParticipants.Add(new DmParticipant { ConversationId = conversation.Id, UserId = userHi });

            await db.SaveChangesAsync(ct);
            await transaction.CommitAsync(ct);
            return new ConversationCreateResult(conversation, Created: true);
        }
        catch (DbUpdateException ex) when (IsUniquePairViolation(ex))
        {
            await transaction.RollbackAsync(ct);
            db.ChangeTracker.Clear();

            var existing = await db.DmConversations.AsNoTracking()
                .FirstAsync(c => c.UserLo == userLo && c.UserHi == userHi, ct);
            return new ConversationCreateResult(existing, Created: false);
        }
    }

    private static bool IsUniquePairViolation(DbUpdateException ex) =>
        ex.InnerException is PostgresException { SqlState: PostgresErrorCodes.UniqueViolation };
}
