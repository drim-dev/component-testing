using StackExchange.Redis;

namespace Relay.Api.Features.Feed;

/// <summary>
/// Per-user unread counters (spec/03-schema.md "Not in PostgreSQL"):
/// key <c>unread:{userId}:{channelId}</c> = integer. Incremented by the Kafka feed-fanout
/// consumer — only on the FIRST successful feed insert, so counter and feed cannot
/// diverge under event redelivery (G-KAFKA's consumer-side pin). Reset by
/// <c>POST /channels/{id}/read</c>.
/// </summary>
public sealed class UnreadCounters(IConnectionMultiplexer redis)
{
    private static RedisKey Key(string userId, string channelId) => $"unread:{userId}:{channelId}";

    public Task Increment(string userId, string channelId) =>
        redis.GetDatabase().StringIncrementAsync(Key(userId, channelId));

    public Task Reset(string userId, string channelId) =>
        redis.GetDatabase().KeyDeleteAsync(Key(userId, channelId));

    /// <summary>All non-zero counters for one user. SCAN is fine at this product's scale.</summary>
    public async Task<IReadOnlyDictionary<string, long>> Snapshot(string userId)
    {
        var server = redis.GetServers()[0];
        var db = redis.GetDatabase();
        var prefix = $"unread:{userId}:";
        var counters = new Dictionary<string, long>(StringComparer.Ordinal);

        await foreach (var key in server.KeysAsync(db.Database, pattern: $"{prefix}*"))
        {
            var channelId = ((string)key!)[prefix.Length..];
            counters[channelId] = (long)await db.StringGetAsync(key);
        }

        return counters;
    }
}
