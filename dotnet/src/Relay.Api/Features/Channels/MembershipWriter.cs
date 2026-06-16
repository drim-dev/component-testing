using Relay.Api.Database;
using Relay.Api.Domain.Channels;

namespace Relay.Api.Features.Channels;

/// <summary>
/// Membership writes (join / add / kick / leave) — the G-CACHE seam. The correct
/// implementation pairs every PostgreSQL membership write with the Redis cache
/// invalidation, so a removed member loses access immediately. The naive variant updates
/// PostgreSQL and forgets the invalidation — the removed member keeps reading from the
/// stale cache until its TTL (300 s) expires.
/// </summary>
public interface IMembershipWriter
{
    Task Add(ChannelMember membership, CancellationToken ct);

    Task Remove(ChannelMember membership, CancellationToken ct);
}

public sealed class MembershipWriter(AppDbContext db, MembershipCache cache) : IMembershipWriter
{
    public async Task Add(ChannelMember membership, CancellationToken ct)
    {
        db.ChannelMembers.Add(membership);
        await db.SaveChangesAsync(ct);
        await cache.Invalidate(membership.ChannelId);
    }

    public async Task Remove(ChannelMember membership, CancellationToken ct)
    {
        db.ChannelMembers.Remove(membership);
        await db.SaveChangesAsync(ct);
        await cache.Invalidate(membership.ChannelId);
    }
}
