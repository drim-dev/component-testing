using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Exceptions;
using Relay.Api.Common.Http;
using Relay.Api.Common.Pagination;
using Relay.Api.Database;

namespace Relay.Api.Features.Messages;

public static class GetConversationMessages
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapGet("/dm/conversations/{id}/messages", async (
                string id,
                string? limit,
                string? before,
                ISender sender,
                CancellationToken ct) =>
                Results.Ok(await sender.Send(new Request(id, limit, before), ct)));
        }
    }

    public sealed record Request(string ConversationId, string? Limit, string? Before)
        : IRequest<PageResponse<MessageDto>>;

    public sealed record MessageDto(string Id, string ConversationId, string SenderId, string Text, DateTime CreatedAt);

    public sealed class RequestHandler(CurrentUser currentUser, IDmAccess access, AppDbContext db)
        : IRequestHandler<Request, PageResponse<MessageDto>>
    {
        public async Task<PageResponse<MessageDto>> Handle(Request request, CancellationToken ct)
        {
            _ = await access.GetForParticipant(request.ConversationId, currentUser.RequireUserId(), ct)
                ?? throw new NotFoundException("dm:conversation:not_found", "Conversation not found.");

            var limit = Paging.ParseLimit(request.Limit);
            var scope = db.DmMessages.AsNoTracking().Where(m => m.ConversationId == request.ConversationId);

            return await Paging.Page(
                scope,
                m => m.Id,
                m => new MessageDto(m.Id, m.ConversationId, m.SenderId, m.Text, m.CreatedAt),
                dto => dto.Id,
                request.Before,
                limit,
                ct);
        }
    }
}
