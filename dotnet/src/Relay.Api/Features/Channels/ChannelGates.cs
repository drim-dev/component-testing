using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Exceptions;
using Relay.Api.Database;
using Relay.Api.Domain.Channels;

namespace Relay.Api.Features.Channels;

/// <summary>
/// The pure role-ordering predicate (owner &gt; admin &gt; member). Unit-testable and
/// correct on its own; the gallery's BOLA-ROLE bug is a route that never consults it.
/// </summary>
public static class ChannelRoles
{
    public static bool AtLeast(ChannelRole actual, ChannelRole required) => actual >= required;
}

/// <summary>
/// Read authorization for channels (the G-BOLA-READ seam). Encapsulates the locked
/// 404/403 policy so it is exercised by the assembled route, not asserted in a mock:
/// existence-hidden private resources 404; visible-but-forbidden actions 403.
/// </summary>
public interface IChannelReadGate
{
    /// <summary>Metadata read: any member or any caller of a public channel. Private + non-member → 404.</summary>
    Task<Channel> AuthorizeMetadata(string channelId, string userId, CancellationToken ct);

    /// <summary>Message read: membership required. Private + non-member → 404; public + non-member → 403.</summary>
    Task<Channel> AuthorizeMessageRead(string channelId, string userId, CancellationToken ct);
}

/// <summary>
/// Role-gated actions (the G-BOLA-ROLE seam). Resolves visibility, then membership,
/// then role — and returns the caller's membership only when all three hold.
/// </summary>
public interface IChannelRoleGate
{
    Task<ChannelMember> Authorize(string channelId, string userId, ChannelRole required, CancellationToken ct);
}

public sealed class ChannelReadGate(AppDbContext db, MembershipCache cache) : IChannelReadGate
{
    public async Task<Channel> AuthorizeMetadata(string channelId, string userId, CancellationToken ct)
    {
        var channel = await LoadOrHide(channelId, ct);

        if (channel.Private && !await IsMember(channelId, userId, ct))
        {
            throw NotFound();
        }

        return channel;
    }

    public async Task<Channel> AuthorizeMessageRead(string channelId, string userId, CancellationToken ct)
    {
        var channel = await LoadOrHide(channelId, ct);

        if (await IsMember(channelId, userId, ct))
        {
            return channel;
        }

        if (channel.Private)
        {
            throw NotFound();
        }

        throw new ForbiddenException("channel:membership_required", "Membership is required to read this channel's messages.");
    }

    private async Task<Channel> LoadOrHide(string channelId, CancellationToken ct)
    {
        var channel = await db.Channels.AsNoTracking().FirstOrDefaultAsync(c => c.Id == channelId, ct);
        return channel ?? throw NotFound();
    }

    /// <summary>
    /// The cached authorization fast path (G-CACHE's blast radius): a hit answers from
    /// Redis; a miss loads from PostgreSQL and warms the cache. Trusting the cache is
    /// what makes a forgotten invalidation on the write path a security bug, not a
    /// performance bug.
    /// </summary>
    private async Task<bool> IsMember(string channelId, string userId, CancellationToken ct)
    {
        var cached = await cache.IsMember(channelId, userId);
        if (cached is not null)
        {
            return cached.Value;
        }

        var members = await db.ChannelMembers.AsNoTracking()
            .Where(m => m.ChannelId == channelId)
            .Select(m => m.UserId)
            .ToListAsync(ct);
        await cache.Warm(channelId, members);
        return members.Contains(userId);
    }

    internal static NotFoundException NotFound() =>
        new("channel:not_found", "Channel not found.");
}

public sealed class ChannelRoleGate(AppDbContext db) : IChannelRoleGate
{
    public async Task<ChannelMember> Authorize(string channelId, string userId, ChannelRole required, CancellationToken ct)
    {
        var channel = await db.Channels.AsNoTracking().FirstOrDefaultAsync(c => c.Id == channelId, ct)
                      ?? throw ChannelReadGate.NotFound();
        var membership = await db.ChannelMembers.AsNoTracking()
            .FirstOrDefaultAsync(m => m.ChannelId == channelId && m.UserId == userId, ct);

        if (membership is null)
        {
            throw channel.Private
                ? ChannelReadGate.NotFound()
                : new ForbiddenException("channel:membership_required", "Membership is required for this action.");
        }

        if (!ChannelRoles.AtLeast(membership.Role, required))
        {
            throw new ForbiddenException("channel:role:forbidden", "Your role does not permit this action.");
        }

        return membership;
    }
}
