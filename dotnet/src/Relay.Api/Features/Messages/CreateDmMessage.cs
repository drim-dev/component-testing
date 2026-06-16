using FluentValidation;
using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Exceptions;
using Relay.Api.Common.Http;
using Relay.Api.Common.Identity;
using Relay.Api.Common.Text;
using Relay.Api.Database;
using Relay.Api.Domain.Messages;
using Relay.Api.Features.Notifications;

namespace Relay.Api.Features.Messages;

public static class CreateDmMessage
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapPost("/dm/conversations/{id}/messages", async (
                string id, Body body, ISender sender, CancellationToken ct) =>
            {
                var response = await sender.Send(new Request(id, body.Text), ct);
                return Results.Created($"/dm/conversations/{id}/messages/{response.Id}", response);
            });
        }

        public sealed record Body(string Text);
    }

    public sealed record Request(string ConversationId, string Text) : IRequest<Response>;

    public sealed record Response(string Id, string ConversationId, string SenderId, string Text, DateTime CreatedAt);

    public sealed class RequestValidator : AbstractValidator<Request>
    {
        public RequestValidator()
        {
            RuleFor(x => x.Text)
                .NotEmpty().WithErrorCode("message:text:invalid")
                .WithMessage("text must be 1-4000 characters.")
                .MaximumLength(4000).WithErrorCode("message:text:invalid")
                .WithMessage("text must be 1-4000 characters.");
        }
    }

    public sealed class RequestHandler(
        CurrentUser currentUser,
        IDmAccess access,
        INotificationJobs jobs,
        AppDbContext db,
        IdFactory ids)
        : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            var caller = currentUser.RequireUserId();
            var conversation = await access.GetForParticipant(request.ConversationId, caller, ct)
                ?? throw new NotFoundException("dm:conversation:not_found", "Conversation not found.");

            var message = new DmMessage
            {
                Id = ids.Create(),
                ConversationId = conversation.Id,
                SenderId = caller,
                Text = request.Text,
                CreatedAt = DateTime.UtcNow,
            };
            db.DmMessages.Add(message);
            await db.SaveChangesAsync(ct);

            // Pinned ordering (02-api.md §2): the job is published AFTER the message
            // commit, with an awaited broker ack — the consumer can never race an
            // uncommitted dm_messages row into its FK. A publish failure here is a 500
            // and the message stays.
            var recipient = conversation.UserLo == caller ? conversation.UserHi : conversation.UserLo;
            await jobs.Enqueue(new NotificationJob(
                message.Id, conversation.Id, caller, recipient, Preview.Of(message.Text)), ct);

            return new Response(message.Id, message.ConversationId, message.SenderId, message.Text, message.CreatedAt);
        }
    }
}
