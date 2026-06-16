using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Http;

namespace Relay.Api.Features.Feed;

public static class GetUnread
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapGet("/me/unread", async (ISender sender, CancellationToken ct) =>
                Results.Ok(await sender.Send(new Request(), ct)));
        }
    }

    public sealed record Request : IRequest<Response>;

    public sealed record Response(IReadOnlyDictionary<string, long> Channels);

    public sealed class RequestHandler(CurrentUser currentUser, UnreadCounters counters)
        : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            var channels = await counters.Snapshot(currentUser.RequireUserId());
            return new Response(channels);
        }
    }
}
