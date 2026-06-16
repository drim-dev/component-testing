using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Http;
using Relay.Api.Common.Pagination;
using Relay.Api.Database;

namespace Relay.Api.Features.Feed;

public static class GetFeed
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapGet("/feed", async (string? limit, string? before, ISender sender, CancellationToken ct) =>
                Results.Ok(await sender.Send(new Request(limit, before), ct)));
        }
    }

    public sealed record Request(string? Limit, string? Before) : IRequest<PageResponse<FeedEntryDto>>;

    public sealed record FeedEntryDto(
        string ChannelId,
        string MessageId,
        string SenderId,
        string Preview,
        DateTime CreatedAt);

    public sealed class RequestHandler(CurrentUser currentUser, AppDbContext db)
        : IRequestHandler<Request, PageResponse<FeedEntryDto>>
    {
        public async Task<PageResponse<FeedEntryDto>> Handle(Request request, CancellationToken ct)
        {
            var caller = currentUser.RequireUserId();
            var limit = Paging.ParseLimit(request.Limit);
            var scope = db.FeedEntries.AsNoTracking().Where(f => f.UserId == caller);

            // The feed item exposes no row id, so the cursor is the (time-ordered)
            // messageId — unique per user via the UNIQUE (user_id, message_id) pair.
            return await Paging.Page(
                scope,
                f => f.MessageId,
                f => new FeedEntryDto(f.ChannelId, f.MessageId, f.SenderId, f.Preview, f.CreatedAt),
                dto => dto.MessageId,
                request.Before,
                limit,
                ct);
        }
    }
}
