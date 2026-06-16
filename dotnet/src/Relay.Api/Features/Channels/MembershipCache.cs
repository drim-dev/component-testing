using StackExchange.Redis;

namespace Relay.Api.Features.Channels;

/// <summary>
/// The Redis membership cache (spec/03-schema.md "Not in PostgreSQL"):
/// key <c>members:{channelId}</c> = set of member user ids, TTL 300 s. It is the
/// authorization fast path the read gate consults — which is exactly why a missed
/// invalidation (gallery case G-CACHE) keeps a kicked member reading until the TTL.
/// </summary>
public sealed class MembershipCache(IConnectionMultiplexer redis)
{
    private static readonly TimeSpan Ttl = TimeSpan.FromSeconds(300);

    private static RedisKey Key(string channelId) => $"members:{channelId}";

    /// <summary>Cached membership check; <c>null</c> = cache miss (key absent or expired).</summary>
    public async Task<bool?> IsMember(string channelId, string userId)
    {
        // One SMEMBERS round trip: an empty result means "no key" (member sets are never
        // empty — every channel has an owner), so existence and lookup stay atomic.
        var members = await redis.GetDatabase().SetMembersAsync(Key(channelId));
        if (members.Length == 0)
        {
            return null;
        }

        return Array.Exists(members, m => m == userId);
    }

    public async Task Warm(string channelId, IReadOnlyCollection<string> memberIds)
    {
        if (memberIds.Count == 0)
        {
            return;
        }

        var db = redis.GetDatabase();
        var key = Key(channelId);
        await db.SetAddAsync(key, memberIds.Select(id => (RedisValue)id).ToArray());
        await db.KeyExpireAsync(key, Ttl);
    }

    /// <summary>The write-path duty G-CACHE's naive variant forgets.</summary>
    public Task Invalidate(string channelId) =>
        redis.GetDatabase().KeyDeleteAsync(Key(channelId));
}
