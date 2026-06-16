using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Exceptions;
using Relay.Api.Common.Http;
using Relay.Api.Database;
using Relay.Api.Domain.Channels;

namespace Relay.Api.Features.Channels;

public static class JoinChannel
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapPost("/channels/{id}/join", async (string id, ISender sender, CancellationToken ct) =>
            {
                var response = await sender.Send(new Request(id), ct);
                return Results.Created($"/channels/{id}/members/{response.UserId}", response);
            });
        }
    }

    public sealed record Request(string ChannelId) : IRequest<Response>;

    public sealed record Response(string ChannelId, string UserId, string Role, DateTime JoinedAt);

    public sealed class RequestHandler(CurrentUser currentUser, IMembershipWriter membershipWriter, AppDbContext db)
        : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            var caller = currentUser.RequireUserId();
            var channel = await db.Channels.AsNoTracking().FirstOrDefaultAsync(c => c.Id == request.ChannelId, ct)
                          ?? throw ChannelReadGate.NotFound();

            var membership = await db.ChannelMembers.AsNoTracking()
                .FirstOrDefaultAsync(m => m.ChannelId == request.ChannelId && m.UserId == caller, ct);

            if (membership is not null)
            {
                throw new ConflictException("channel:member:already", "You are already a member of this channel.");
            }

            if (channel.Private)
            {
                throw ChannelReadGate.NotFound();
            }

            var now = DateTime.UtcNow;
            await membershipWriter.Add(new ChannelMember
            {
                ChannelId = channel.Id,
                UserId = caller,
                Role = ChannelRole.Member,
                JoinedAt = now,
            }, ct);

            return new Response(channel.Id, caller, ChannelRoleNames.Member, now);
        }
    }
}
