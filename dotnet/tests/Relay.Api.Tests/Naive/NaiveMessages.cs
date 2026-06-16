using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Identity;
using Relay.Api.Database;
using Relay.Api.Domain.Messages;
using Relay.Api.Features.Messages;

namespace Relay.Api.Tests.Naive;

// These are the DEFAULT-SHAPED implementations an agent ships when nobody pins the
// behavior (sourced from spec/05-gallery.md, NOT arbitrary mutations). They are wired
// into exactly one demonstration each via TestFixture.NaiveClient and never into the
// shipped suite. Each is paired with the catching test that goes red against it.

/// <summary>
/// G-IDOR naive variant: loads the conversation by id only. <c>IsParticipant</c> exists
/// and is correct — it is simply never called here ("correct logic, missing wiring").
/// </summary>
public sealed class NaiveDmAccess(AppDbContext db) : IDmAccess
{
    public async Task<DmConversation?> GetForParticipant(string conversationId, string userId, CancellationToken ct) =>
        await db.DmConversations.AsNoTracking().FirstOrDefaultAsync(c => c.Id == conversationId, ct);
}

/// <summary>
/// G-RACE naive variant: check-then-insert with no conflict handling — the TOCTOU window.
/// Sequential callers see the existing row; concurrent callers all miss it, both insert,
/// and the loser hits the unique constraint as an unhandled 500.
/// </summary>
public sealed class NaiveRaceConversationWriter(AppDbContext db, IdFactory ids) : IConversationWriter
{
    public async Task<ConversationCreateResult> Create(string userLo, string userHi, CancellationToken ct)
    {
        var existing = await db.DmConversations.AsNoTracking()
            .FirstOrDefaultAsync(c => c.UserLo == userLo && c.UserHi == userHi, ct);
        if (existing is not null)
        {
            return new ConversationCreateResult(existing, Created: false);
        }

        // Test-only window widening (permitted by spec/05-gallery.md G-RACE): in-process
        // requests can complete faster than the natural TOCTOU window, making the demo
        // flaky. This delay makes the check-then-insert race open DETERMINISTICALLY — it
        // does not change the shape of the bug (a missing unique-conflict handler), only
        // its timing. Documented in spec §0.4.
        await Task.Delay(100, ct);

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

        return new ConversationCreateResult(conversation, Created: true);
    }
}

/// <summary>
/// G-TX naive variant: three sequential saves, no transaction — "call repo.save three
/// times". A mid-write failure leaves an orphan conversation and one participant behind.
/// </summary>
public sealed class NaiveTxConversationWriter(AppDbContext db, IdFactory ids) : IConversationWriter
{
    public async Task<ConversationCreateResult> Create(string userLo, string userHi, CancellationToken ct)
    {
        var conversation = new DmConversation
        {
            Id = ids.Create(),
            UserLo = userLo,
            UserHi = userHi,
            CreatedAt = DateTime.UtcNow,
        };
        db.DmConversations.Add(conversation);
        await db.SaveChangesAsync(ct);

        db.DmParticipants.Add(new DmParticipant { ConversationId = conversation.Id, UserId = userLo });
        await db.SaveChangesAsync(ct);

        db.DmParticipants.Add(new DmParticipant { ConversationId = conversation.Id, UserId = userHi });
        await db.SaveChangesAsync(ct);

        return new ConversationCreateResult(conversation, Created: true);
    }
}
