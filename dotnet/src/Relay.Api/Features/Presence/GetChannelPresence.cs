using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Http;
using Relay.Api.Database;
using Relay.Api.Domain.Channels;
using Relay.Api.Features.Channels;

namespace Relay.Api.Features.Presence;

/// <summary>
/// <c>GET /channels/{id}/presence</c> — the server-streaming gRPC path and the G-GRPC
/// hero. Authorization mirrors message-read (member → 200; public + non-member → 403;
/// private/unknown → 404). The presence client consumes the stream to clean end-of-stream
/// and turns a mid-stream failure into 502 <c>presence:incomplete</c> — so a partial member
/// list is NEVER returned as complete (the gallery bug).
/// </summary>
public static class GetChannelPresence
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapGet("/channels/{id}/presence", async (string id, ISender sender, CancellationToken ct) =>
                Results.Ok(await sender.Send(new Request(id), ct)));
        }
    }

    public sealed record Request(string ChannelId) : IRequest<Response>;

    public sealed record Response(IReadOnlyList<Member> Members);

    public sealed record Member(string UserId, string Status);

    public sealed class RequestHandler(
        CurrentUser currentUser,
        IChannelReadGate readGate,
        AppDbContext db,
        IPresenceClient presence)
        : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            var caller = currentUser.RequireUserId();
            await readGate.AuthorizeMessageRead(request.ChannelId, caller, ct);

            var memberIds = await db.ChannelMembers.AsNoTracking()
                .Where(m => m.ChannelId == request.ChannelId)
                .OrderBy(m => m.UserId)
                .Select(m => m.UserId)
                .ToListAsync(ct);

            var presences = await presence.StreamPresence(memberIds, ct);
            var members = presences
                .Select(p => new Member(p.UserId, p.Online ? "online" : "offline"))
                .ToList();
            return new Response(members);
        }
    }
}
