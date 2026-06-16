using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Exceptions;
using Relay.Api.Common.Http;
using Relay.Api.Common.Identity;
using Relay.Api.Database;
using Relay.Api.Domain.Attachments;
using Relay.Api.Domain.Channels;
using Relay.Api.Features.Channels;

namespace Relay.Api.Features.Attachments;

public static class UploadAttachment
{
    public const long MaxSizeBytes = 1024 * 1024;

    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapPost("/channels/{id}/attachments", async (
                    string id, IFormFile file, ISender sender, CancellationToken ct) =>
                {
                    using var buffer = new MemoryStream();
                    await file.CopyToAsync(buffer, ct);
                    var response = await sender.Send(new Request(id, file.FileName, buffer.ToArray()), ct);
                    return Results.Created($"/attachments/{response.Id}", response);
                })
                .DisableAntiforgery();
        }
    }

    public sealed record Request(string ChannelId, string Filename, byte[] Content) : IRequest<Response>;

    public sealed record Response(string Id, string ChannelId, string Filename, long SizeBytes, DateTime CreatedAt);

    public sealed class RequestHandler(
        CurrentUser currentUser,
        IChannelRoleGate roleGate,
        IAttachmentStore store,
        AppDbContext db,
        IdFactory ids)
        : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            var caller = currentUser.RequireUserId();
            await roleGate.Authorize(request.ChannelId, caller, ChannelRole.Member, ct);

            if (request.Content.Length == 0)
            {
                throw new ValidationException("attachment:empty", "The uploaded file is empty.");
            }

            if (request.Content.Length > MaxSizeBytes)
            {
                throw new PayloadTooLargeException("attachment:too_large", "Attachments are limited to 1 MiB.");
            }

            var attachment = new Attachment
            {
                Id = ids.Create(),
                ChannelId = request.ChannelId,
                UploaderId = caller,
                Filename = request.Filename,
                SizeBytes = request.Content.Length,
                StorageKey = ids.Create(),
                CreatedAt = DateTime.UtcNow,
            };

            // Bytes land in the object store BEFORE the metadata row commits (spec/04 §5):
            // a row pointing at missing bytes is a worse failure than orphaned bytes.
            await store.Save(attachment.StorageKey, request.Content, ct);

            db.Attachments.Add(attachment);
            await db.SaveChangesAsync(ct);

            return new Response(
                attachment.Id, attachment.ChannelId, attachment.Filename, attachment.SizeBytes, attachment.CreatedAt);
        }
    }
}
