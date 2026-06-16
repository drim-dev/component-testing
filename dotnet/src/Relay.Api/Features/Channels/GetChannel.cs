using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Http;
using Relay.Api.Database;

namespace Relay.Api.Features.Channels;

public static class GetChannel
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapGet("/channels/{id}", async (string id, ISender sender, CancellationToken ct) =>
                Results.Ok(await sender.Send(new Request(id), ct)));
        }
    }

    public sealed record Request(string Id) : IRequest<Response>;

    public sealed record Response(string Id, string Name, bool Private, int MemberCount, DateTime CreatedAt);

    public sealed class RequestHandler(CurrentUser currentUser, IChannelReadGate readGate, AppDbContext db)
        : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            var channel = await readGate.AuthorizeMetadata(request.Id, currentUser.RequireUserId(), ct);
            var memberCount = await db.ChannelMembers.AsNoTracking()
                .CountAsync(m => m.ChannelId == channel.Id, ct);

            return new Response(channel.Id, channel.Name, channel.Private, memberCount, channel.CreatedAt);
        }
    }
}
