using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Http;
using Relay.Api.Domain.Channels;
using Relay.Api.Features.Feed;

namespace Relay.Api.Features.Channels;

public static class MarkChannelRead
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapPost("/channels/{id}/read", async (string id, ISender sender, CancellationToken ct) =>
            {
                await sender.Send(new Request(id), ct);
                return Results.NoContent();
            });
        }
    }

    public sealed record Request(string ChannelId) : IRequest;

    public sealed class RequestHandler(CurrentUser currentUser, IChannelRoleGate roleGate, UnreadCounters counters)
        : IRequestHandler<Request>
    {
        public async Task Handle(Request request, CancellationToken ct)
        {
            var caller = currentUser.RequireUserId();
            await roleGate.Authorize(request.ChannelId, caller, ChannelRole.Member, ct);
            await counters.Reset(caller, request.ChannelId);
        }
    }
}
