using FluentValidation;
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

public static class AddMember
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapPost("/channels/{id}/members", async (string id, Body body, ISender sender, CancellationToken ct) =>
            {
                var response = await sender.Send(new Request(id, body.UserId), ct);
                return Results.Created($"/channels/{id}/members/{response.UserId}", response);
            });
        }

        public sealed record Body(string UserId);
    }

    public sealed record Request(string ChannelId, string UserId) : IRequest<Response>;

    public sealed record Response(string ChannelId, string UserId, string Role, DateTime JoinedAt);

    public sealed class RequestValidator : AbstractValidator<Request>
    {
        public RequestValidator()
        {
            RuleFor(x => x.UserId)
                .NotEmpty()
                .WithErrorCode("channel:member:invalid")
                .WithMessage("userId is required.");
        }
    }

    public sealed class RequestHandler(
        CurrentUser currentUser,
        IChannelRoleGate roleGate,
        IMembershipWriter membershipWriter,
        AppDbContext db)
        : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            await roleGate.Authorize(request.ChannelId, currentUser.RequireUserId(), ChannelRole.Admin, ct);

            var targetExists = await db.Users.AsNoTracking().AnyAsync(u => u.Id == request.UserId, ct);
            if (!targetExists)
            {
                throw new NotFoundException("user:not_found", "User not found.");
            }

            var alreadyMember = await db.ChannelMembers.AsNoTracking()
                .AnyAsync(m => m.ChannelId == request.ChannelId && m.UserId == request.UserId, ct);
            if (alreadyMember)
            {
                throw new ConflictException("channel:member:already", "User is already a member of this channel.");
            }

            var now = DateTime.UtcNow;
            await membershipWriter.Add(new ChannelMember
            {
                ChannelId = request.ChannelId,
                UserId = request.UserId,
                Role = ChannelRole.Member,
                JoinedAt = now,
            }, ct);

            return new Response(request.ChannelId, request.UserId, ChannelRoleNames.Member, now);
        }
    }
}
