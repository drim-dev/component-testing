using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Http;
using Relay.Api.Common.Pagination;
using Relay.Api.Database;

namespace Relay.Api.Features.Channels;

public static class ListChannels
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapGet("/channels", async (
                string? limit,
                string? before,
                ISender sender,
                CancellationToken ct) =>
                Results.Ok(await sender.Send(new Request(limit, before), ct)));
        }
    }

    public sealed record Request(string? Limit, string? Before) : IRequest<PageResponse<ChannelDto>>;

    public sealed record ChannelDto(string Id, string Name, bool Private, int MemberCount, DateTime CreatedAt);

    public sealed class RequestHandler(CurrentUser currentUser, AppDbContext db)
        : IRequestHandler<Request, PageResponse<ChannelDto>>
    {
        public async Task<PageResponse<ChannelDto>> Handle(Request request, CancellationToken ct)
        {
            var caller = currentUser.RequireUserId();
            var limit = Paging.ParseLimit(request.Limit);
            var scope = db.Channels.AsNoTracking()
                .Where(c => !c.Private || c.Members.Any(m => m.UserId == caller));

            return await Paging.Page(
                scope,
                c => c.Id,
                c => new ChannelDto(c.Id, c.Name, c.Private, c.Members.Count, c.CreatedAt),
                dto => dto.Id,
                request.Before,
                limit,
                ct);
        }
    }
}
