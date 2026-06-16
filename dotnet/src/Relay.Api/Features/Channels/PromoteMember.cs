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

public static class PromoteMember
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapPost("/channels/{id}/members/{userId}/promote", async (
                string id, string userId, ISender sender, CancellationToken ct) =>
                Results.Ok(await sender.Send(new Request(id, userId), ct)));
        }
    }

    public sealed record Request(string ChannelId, string UserId) : IRequest<Response>;

    public sealed record Response(string ChannelId, string UserId, string Role, DateTime JoinedAt);

    public sealed class RequestHandler(CurrentUser currentUser, IChannelRoleGate roleGate, AppDbContext db)
        : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            await roleGate.Authorize(request.ChannelId, currentUser.RequireUserId(), ChannelRole.Owner, ct);

            var target = await db.ChannelMembers
                .FirstOrDefaultAsync(m => m.ChannelId == request.ChannelId && m.UserId == request.UserId, ct)
                ?? throw new NotFoundException("channel:member:not_found", "Member not found.");

            if (target.Role != ChannelRole.Member)
            {
                throw new ConflictException("channel:member:already", "Member already has an elevated role.");
            }

            target.Role = ChannelRole.Admin;
            await db.SaveChangesAsync(ct);

            return new Response(request.ChannelId, request.UserId, ChannelRoleNames.Admin, target.JoinedAt);
        }
    }
}
