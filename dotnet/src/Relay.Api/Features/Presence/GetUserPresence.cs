using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Exceptions;
using Relay.Api.Common.Http;
using Relay.Api.Database;

namespace Relay.Api.Features.Presence;

/// <summary>
/// <c>GET /users/{id}/presence</c> — the unary gRPC path. Unknown user → 404; otherwise
/// the presence service answers online/offline from its Redis TTL.
/// </summary>
public static class GetUserPresence
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapGet("/users/{id}/presence", async (string id, ISender sender, CancellationToken ct) =>
                Results.Ok(await sender.Send(new Request(id), ct)));
        }
    }

    public sealed record Request(string UserId) : IRequest<Response>;

    public sealed record Response(string UserId, string Status);

    public sealed class RequestHandler(AppDbContext db, IPresenceClient presence)
        : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            var exists = await db.Users.AsNoTracking().AnyAsync(u => u.Id == request.UserId, ct);
            if (!exists)
            {
                throw new NotFoundException("user:not_found", "User not found.");
            }

            var online = await presence.IsOnline(request.UserId, ct);
            return new Response(request.UserId, online ? "online" : "offline");
        }
    }
}
