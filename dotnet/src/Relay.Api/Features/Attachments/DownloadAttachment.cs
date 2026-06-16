using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Http;

namespace Relay.Api.Features.Attachments;

public static class DownloadAttachment
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapGet("/attachments/{id}", async (string id, ISender sender, CancellationToken ct) =>
            {
                var response = await sender.Send(new Request(id), ct);
                return Results.File(response.Content, "application/octet-stream", response.Filename);
            });
        }
    }

    public sealed record Request(string AttachmentId) : IRequest<Response>;

    public sealed record Response(byte[] Content, string Filename);

    public sealed class RequestHandler(CurrentUser currentUser, IAttachmentAccess access, IAttachmentStore store)
        : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            var attachment = await access.Authorize(request.AttachmentId, currentUser.RequireUserId(), ct);
            var content = await store.Read(attachment.StorageKey, ct);
            return new Response(content, attachment.Filename);
        }
    }
}
