using Relay.Api.Database;
using Relay.Api.Domain.Channels;
using Relay.Api.Features.Channels;

namespace Relay.Api.Tests.Naive;

/// <summary>
/// G-CACHE naive variant: the membership write updates PostgreSQL and FORGETS the Redis
/// cache invalidation. The invalidation helper exists, is correct, and is never called —
/// so a kicked member keeps reading from the stale <c>members:{channelId}</c> set until
/// its TTL (300 s) expires.
/// </summary>
public sealed class NaiveMembershipWriter(AppDbContext db) : IMembershipWriter
{
    public async Task Add(ChannelMember membership, CancellationToken ct)
    {
        db.ChannelMembers.Add(membership);
        await db.SaveChangesAsync(ct);
    }

    public async Task Remove(ChannelMember membership, CancellationToken ct)
    {
        db.ChannelMembers.Remove(membership);
        await db.SaveChangesAsync(ct);
    }
}
