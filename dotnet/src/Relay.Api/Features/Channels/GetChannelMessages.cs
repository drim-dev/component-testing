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

public static class GetChannelMessages
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapGet("/channels/{id}/messages", async (
                string id,
                string? limit,
                string? before,
                ISender sender,
                CancellationToken ct) =>
                Results.Ok(await sender.Send(new Request(id, limit, before), ct)));
        }
    }

    public sealed record Request(string ChannelId, string? Limit, string? Before)
        : IRequest<PageResponse<MessageDto>>;

    public sealed record MessageDto(
        string Id,
        string ChannelId,
        string SenderId,
        string Text,
        string? LinkPreviewTitle,
        DateTime CreatedAt);

    public sealed class RequestHandler(CurrentUser currentUser, IChannelReadGate readGate, AppDbContext db)
        : IRequestHandler<Request, PageResponse<MessageDto>>
    {
        public async Task<PageResponse<MessageDto>> Handle(Request request, CancellationToken ct)
        {
            await readGate.AuthorizeMessageRead(request.ChannelId, currentUser.RequireUserId(), ct);

            var limit = Paging.ParseLimit(request.Limit);
            var scope = db.ChannelMessages.AsNoTracking().Where(m => m.ChannelId == request.ChannelId);

            return await Paging.Page(
                scope,
                m => m.Id,
                m => new MessageDto(m.Id, m.ChannelId, m.SenderId, m.Text, m.LinkPreviewTitle, m.CreatedAt),
                dto => dto.Id,
                request.Before,
                limit,
                ct);
        }
    }
}
