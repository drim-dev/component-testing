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

/// <summary>
/// Kick (caller removes another member) or leave (caller removes self). Kicking is
/// role-gated through <see cref="IChannelRoleGate"/> — the G-BOLA-ROLE seam.
/// </summary>
public static class RemoveMember
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapDelete("/channels/{id}/members/{userId}", async (
                string id, string userId, ISender sender, CancellationToken ct) =>
            {
                await sender.Send(new Request(id, userId), ct);
                return Results.NoContent();
            });
        }
    }

    public sealed record Request(string ChannelId, string UserId) : IRequest;

    public sealed class RequestHandler(
        CurrentUser currentUser,
        IChannelRoleGate roleGate,
        IMembershipWriter membershipWriter,
        AppDbContext db)
        : IRequestHandler<Request>
    {
        public async Task Handle(Request request, CancellationToken ct)
        {
            var caller = currentUser.RequireUserId();

            if (request.UserId == caller)
            {
                await Leave(request.ChannelId, caller, ct);
                return;
            }

            await Kick(request, caller, ct);
        }

        private async Task Leave(string channelId, string caller, CancellationToken ct)
        {
            var channel = await db.Channels.AsNoTracking().FirstOrDefaultAsync(c => c.Id == channelId, ct)
                          ?? throw ChannelReadGate.NotFound();
            var membership = await db.ChannelMembers
                .FirstOrDefaultAsync(m => m.ChannelId == channelId && m.UserId == caller, ct);

            if (membership is null)
            {
                throw channel.Private
                    ? ChannelReadGate.NotFound()
                    : new NotFoundException("channel:member:not_found", "Member not found.");
            }

            if (membership.Role == ChannelRole.Owner)
            {
                throw new ConflictException("channel:owner:cannot_leave", "The owner cannot leave their own channel.");
            }

            await membershipWriter.Remove(membership, ct);
        }

        private async Task Kick(Request request, string caller, CancellationToken ct)
        {
            var callerMembership = await roleGate.Authorize(request.ChannelId, caller, ChannelRole.Admin, ct);

            var target = await db.ChannelMembers
                .FirstOrDefaultAsync(m => m.ChannelId == request.ChannelId && m.UserId == request.UserId, ct)
                ?? throw new NotFoundException("channel:member:not_found", "Member not found.");

            var kickingPrivilegedMember = target.Role == ChannelRole.Owner
                || (target.Role == ChannelRole.Admin && callerMembership.Role != ChannelRole.Owner);
            if (kickingPrivilegedMember)
            {
                throw new ForbiddenException("channel:role:forbidden", "Your role does not permit removing this member.");
            }

            await membershipWriter.Remove(target, ct);
        }
    }
}
