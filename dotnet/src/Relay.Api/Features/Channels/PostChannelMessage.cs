using FluentValidation;
using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Exceptions;
using Relay.Api.Common.Http;
using Relay.Api.Common.Identity;
using Relay.Api.Common.Text;
using Relay.Api.Database;
using Relay.Api.Domain.Channels;
using ValidationException = Relay.Api.Common.Exceptions.ValidationException;

namespace Relay.Api.Features.Channels;

public static class PostChannelMessage
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapPost("/channels/{id}/messages", async (string id, Body body, ISender sender, CancellationToken ct) =>
            {
                var response = await sender.Send(new Request(id, body.Text, body.AttachmentIds ?? []), ct);
                return Results.Created($"/channels/{id}/messages/{response.Id}", response);
            });
        }

        public sealed record Body(string Text, IReadOnlyList<string>? AttachmentIds);
    }

    public sealed record Request(string ChannelId, string Text, IReadOnlyList<string> AttachmentIds)
        : IRequest<Response>;

    public sealed record Response(
        string Id,
        string ChannelId,
        string SenderId,
        string Text,
        IReadOnlyList<string> AttachmentIds,
        string? LinkPreviewTitle,
        DateTime CreatedAt);

    public sealed class RequestValidator : AbstractValidator<Request>
    {
        public RequestValidator()
        {
            RuleFor(x => x.Text)
                .NotEmpty().WithErrorCode("message:text:invalid")
                .WithMessage("text must be 1-4000 characters.")
                .MaximumLength(4000).WithErrorCode("message:text:invalid")
                .WithMessage("text must be 1-4000 characters.");

            RuleFor(x => x.AttachmentIds)
                .Must(ids => ids.Count <= 10)
                .WithErrorCode("message:attachment:invalid")
                .WithMessage("A message can reference at most 10 attachments.");
        }
    }

    public sealed class RequestHandler(
        CurrentUser currentUser,
        IChannelRoleGate roleGate,
        IMessagePostedPublisher publisher,
        ILinkPreviewer linkPreviewer,
        AppDbContext db,
        IdFactory ids)
        : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            var caller = currentUser.RequireUserId();
            await roleGate.Authorize(request.ChannelId, caller, ChannelRole.Member, ct);

            // Unfurl runs (bounded, graceful) BEFORE the insert so the title persists with
            // the row; a slow or failing upstream degrades to a null preview, never a hang.
            var linkPreviewTitle = await linkPreviewer.TryUnfurl(request.Text, ct);

            var message = new ChannelMessage
            {
                Id = ids.Create(),
                ChannelId = request.ChannelId,
                SenderId = caller,
                Text = request.Text,
                LinkPreviewTitle = linkPreviewTitle,
                CreatedAt = DateTime.UtcNow,
            };

            // Pinned write ordering (02-api.md §3, no outbox — deliberate scope cut):
            // insert → publish AWAITING broker confirmation → commit. A publish failure
            // rolls the insert back and surfaces 503; the message is never half-posted.
            await using var transaction = await db.Database.BeginTransactionAsync(ct);
            db.ChannelMessages.Add(message);
            await db.SaveChangesAsync(ct);
            await LinkAttachments(request, message.Id, caller, ct);

            await publisher.Publish(new MessagePostedEvent(
                message.Id, message.ChannelId, message.SenderId, Preview.Of(message.Text), message.CreatedAt), ct);

            await transaction.CommitAsync(ct);

            return new Response(
                message.Id,
                message.ChannelId,
                message.SenderId,
                message.Text,
                request.AttachmentIds,
                message.LinkPreviewTitle,
                message.CreatedAt);
        }

        /// <summary>
        /// S-AT-04: a message may only reference attachments the CALLER uploaded to THIS
        /// channel. Runs inside the message transaction, so a rejected reference rolls
        /// the whole post back.
        /// </summary>
        private async Task LinkAttachments(Request request, string messageId, string caller, CancellationToken ct)
        {
            if (request.AttachmentIds.Count == 0)
            {
                return;
            }

            var attachments = await db.Attachments
                .Where(a => request.AttachmentIds.Contains(a.Id))
                .ToListAsync(ct);

            var allValid = attachments.Count == request.AttachmentIds.Distinct().Count()
                && attachments.TrueForAll(a =>
                    a.ChannelId == request.ChannelId && a.UploaderId == caller && a.MessageId == null);
            if (!allValid)
            {
                throw new ValidationException(
                    "message:attachment:invalid",
                    "Attachments must be uploaded to this channel by you and not already referenced.");
            }

            foreach (var attachment in attachments)
            {
                attachment.MessageId = messageId;
            }

            await db.SaveChangesAsync(ct);
        }
    }
}
