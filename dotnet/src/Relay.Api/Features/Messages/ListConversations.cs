using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Http;
using Relay.Api.Common.Pagination;
using Relay.Api.Database;

namespace Relay.Api.Features.Messages;

public static class ListConversations
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapGet("/dm/conversations", async (
                string? limit,
                string? before,
                ISender sender,
                CancellationToken ct) =>
                Results.Ok(await sender.Send(new Request(limit, before), ct)));
        }
    }

    public sealed record Request(string? Limit, string? Before) : IRequest<PageResponse<ConversationDto>>;

    public sealed record ConversationDto(string Id, IReadOnlyList<string> ParticipantIds, DateTime CreatedAt);

    public sealed class RequestHandler(CurrentUser currentUser, AppDbContext db)
        : IRequestHandler<Request, PageResponse<ConversationDto>>
    {
        public async Task<PageResponse<ConversationDto>> Handle(Request request, CancellationToken ct)
        {
            var caller = currentUser.RequireUserId();
            var limit = Paging.ParseLimit(request.Limit);
            var scope = db.DmConversations.AsNoTracking()
                .Where(c => c.UserLo == caller || c.UserHi == caller);

            return await Paging.Page(
                scope,
                c => c.Id,
                c => new ConversationDto(c.Id, new[] { c.UserLo, c.UserHi }, c.CreatedAt),
                dto => dto.Id,
                request.Before,
                limit,
                ct);
        }
    }
}
