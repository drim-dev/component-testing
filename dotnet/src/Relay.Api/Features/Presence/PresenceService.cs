using Grpc.Core;
using Relay.Api.Features.Presence.Grpc;
using StackExchange.Redis;

namespace Relay.Api.Features.Presence;

/// <summary>
/// The companion-owned presence gRPC service (spec/04-dependencies.md §8) — NOT a
/// third-party. Presence lives in Redis under <c>presence:{userId}</c> with a 60 s TTL
/// (set by the heartbeat); the unary RPC reads one key, the streaming RPC emits exactly
/// one <see cref="PresenceStatus"/> per requested user then closes cleanly.
/// </summary>
/// <remarks>
/// The streaming RPC honors a test-only fault (<see cref="PresenceStreamFault"/>): when
/// armed to "fail after N", it writes N statuses then aborts the stream with a gRPC error.
/// This is the deterministic partial-stream probe the G-GRPC catch needs — a real
/// mid-stream transport failure, not an in-process mock. The fault is a no-op in
/// production (never armed); the service code path is identical either way.
/// </remarks>
public sealed class PresenceService(IConnectionMultiplexer redis, PresenceStreamFault fault)
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
        var emitted = 0;
        foreach (var userId in request.UserIds)
        {
            if (fault.ShouldFailAfter is { } limit && emitted >= limit)
            {
                throw new RpcException(new Status(
                    StatusCode.Unavailable, "presence stream fault (test-only): aborting mid-stream"));
            }

            var online = await db.KeyExistsAsync(KeyPrefix + userId);
            await responseStream.WriteAsync(new PresenceStatus { UserId = userId, Online = online });
            emitted++;
        }
    }
}

/// <summary>
/// Test-only fault control for <see cref="PresenceService"/> (spec/04-dependencies.md §8
/// "fault control"). A singleton the harness arms before a request and clears on reset.
/// Unset (the production default) → the stream always completes cleanly.
/// </summary>
public sealed class PresenceStreamFault
{
    public int? ShouldFailAfter { get; private set; }

    public void FailAfter(int messages) => ShouldFailAfter = messages;

    public void Clear() => ShouldFailAfter = null;
}
