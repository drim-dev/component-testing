using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Http;
using StackExchange.Redis;

namespace Relay.Api.Features.Presence;

/// <summary>
/// <c>POST /me/heartbeat</c> — marks the caller online for 60 s. Writes the same Redis key
/// the presence service reads (<c>presence:{userId}</c>), so a heartbeat is observable
/// through the gRPC unary and streaming paths alike.
/// </summary>
public static class Heartbeat
{
    public static readonly TimeSpan Ttl = TimeSpan.FromSeconds(60);

    public static RedisKey Key(string userId) => PresenceService.KeyPrefix + userId;

    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapPost("/me/heartbeat", async (ISender sender, CancellationToken ct) =>
            {
                await sender.Send(new Request(), ct);
                return Results.NoContent();
            });
        }
    }

    public sealed record Request : IRequest;

    public sealed class RequestHandler(CurrentUser currentUser, IConnectionMultiplexer redis)
        : IRequestHandler<Request>
    {
        public async Task Handle(Request request, CancellationToken ct)
        {
            var caller = currentUser.RequireUserId();
            await redis.GetDatabase().StringSetAsync(Key(caller), "1", Ttl);
        }
    }
}
