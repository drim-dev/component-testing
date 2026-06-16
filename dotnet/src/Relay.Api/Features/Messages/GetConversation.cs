using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Exceptions;
using Relay.Api.Common.Http;

namespace Relay.Api.Features.Messages;

public static class GetConversation
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapGet("/dm/conversations/{id}", async (string id, ISender sender, CancellationToken ct) =>
                Results.Ok(await sender.Send(new Request(id), ct)));
        }
    }

    public sealed record Request(string Id) : IRequest<Response>;

    public sealed record Response(string Id, IReadOnlyList<string> ParticipantIds, DateTime CreatedAt);

    public sealed class RequestHandler(CurrentUser currentUser, IDmAccess access) : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            var conversation = await access.GetForParticipant(request.Id, currentUser.RequireUserId(), ct)
                               ?? throw new NotFoundException("dm:conversation:not_found", "Conversation not found.");

            return new Response(
                conversation.Id,
                [conversation.UserLo, conversation.UserHi],
                conversation.CreatedAt);
        }
    }
}
