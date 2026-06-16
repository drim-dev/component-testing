using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Http;
using Relay.Api.Common.Pagination;
using Relay.Api.Database;

namespace Relay.Api.Features.Notifications;

public static class GetNotifications
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapGet("/notifications", async (string? limit, string? before, ISender sender, CancellationToken ct) =>
                Results.Ok(await sender.Send(new Request(limit, before), ct)));
        }
    }

    public sealed record Request(string? Limit, string? Before) : IRequest<PageResponse<NotificationDto>>;

    public sealed record NotificationDto(
        string Id,
        string Type,
        string DmMessageId,
        string ConversationId,
        string SenderId,
        string Preview,
        DateTime CreatedAt);

    public sealed class RequestHandler(CurrentUser currentUser, AppDbContext db)
        : IRequestHandler<Request, PageResponse<NotificationDto>>
    {
        public async Task<PageResponse<NotificationDto>> Handle(Request request, CancellationToken ct)
        {
            var caller = currentUser.RequireUserId();
            var limit = Paging.ParseLimit(request.Limit);
            var scope = db.Notifications.AsNoTracking().Where(n => n.UserId == caller);

            return await Paging.Page(
                scope,
                n => n.Id,
                n => new NotificationDto(
                    n.Id, "dm.message", n.DmMessageId, n.ConversationId, n.SenderId, n.Preview, n.CreatedAt),
                dto => dto.Id,
                request.Before,
                limit,
                ct);
        }
    }
}
