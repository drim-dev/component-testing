using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Http;
using Relay.Api.Database;
using Relay.Api.Domain.Channels;

namespace Relay.Api.Features.Channels;

public static class DeleteChannel
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapDelete("/channels/{id}", async (string id, ISender sender, CancellationToken ct) =>
            {
                await sender.Send(new Request(id), ct);
                return Results.NoContent();
            });
        }
    }

    public sealed record Request(string ChannelId) : IRequest;

    public sealed class RequestHandler(CurrentUser currentUser, IChannelRoleGate roleGate, AppDbContext db)
        : IRequestHandler<Request>
    {
        public async Task Handle(Request request, CancellationToken ct)
        {
            await roleGate.Authorize(request.ChannelId, currentUser.RequireUserId(), ChannelRole.Owner, ct);

            await db.Channels.Where(c => c.Id == request.ChannelId).ExecuteDeleteAsync(ct);
        }
    }
}
