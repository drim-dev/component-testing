using Grpc.Core;
using Relay.Api.Features.Presence.Grpc;
using StackExchange.Redis;

namespace Relay.Api.Features.Presence;

/// <summary>
/// The companion-owned presence gRPC service (spec/04-dependencies.md §8) — the neighbour's
/// own production code. Presence lives in Redis under <c>presence:{userId}</c> with a 60 s
/// TTL (set by the heartbeat); the unary RPC reads one key, the streaming RPC emits exactly
/// one <see cref="PresenceStatus"/> per requested user then closes cleanly. In a component
/// test of the Relay API this neighbour is stubbed (see <c>PresenceHarness</c>), not run —
/// this class is the real implementation it stands in for.
/// </summary>
public sealed class PresenceService(IConnectionMultiplexer redis)
    : Grpc.Presence.PresenceBase
{
    public const string KeyPrefix = "presence:";

    public override async Task<PresenceStatus> GetPresence(GetPresenceRequest request, ServerCallContext context)
    {
        var online = await redis.GetDatabase().KeyExistsAsync(KeyPrefix + request.UserId);
        return new PresenceStatus { UserId = request.UserId, Online = online };
    }

    public override async Task StreamChannelPresence(
        StreamChannelPresenceRequest request,
        IServerStreamWriter<PresenceStatus> responseStream,
        ServerCallContext context)
    {
        var db = redis.GetDatabase();
        foreach (var userId in request.UserIds)
        {
            var online = await db.KeyExistsAsync(KeyPrefix + userId);
            await responseStream.WriteAsync(new PresenceStatus { UserId = userId, Online = online });
        }
    }
}
