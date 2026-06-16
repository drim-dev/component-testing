using FluentValidation;
using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Exceptions;
using Relay.Api.Common.Http;
using Relay.Api.Database;
using ValidationException = Relay.Api.Common.Exceptions.ValidationException;

namespace Relay.Api.Features.Messages;

public static class CreateConversation
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapPost("/dm/conversations", async (Body body, ISender sender, CancellationToken ct) =>
            {
                var result = await sender.Send(new Request(body.RecipientId), ct);
                return result.Created
                    ? Results.Created($"/dm/conversations/{result.Body.Id}", result.Body)
                    : Results.Ok(result.Body);
            });
        }

        public sealed record Body(string RecipientId);
    }

    public sealed record Request(string RecipientId) : IRequest<Result>;

    public sealed record Result(Response Body, bool Created);

    public sealed record Response(string Id, IReadOnlyList<string> ParticipantIds, DateTime CreatedAt);

    public sealed class RequestValidator : AbstractValidator<Request>
    {
        public RequestValidator()
        {
            RuleFor(x => x.RecipientId)
                .NotEmpty()
                .WithErrorCode("dm:recipient:invalid")
                .WithMessage("recipientId is required.");
        }
    }

    public sealed class RequestHandler(AppDbContext db, CurrentUser currentUser, IConversationWriter writer)
        : IRequestHandler<Request, Result>
    {
        public async Task<Result> Handle(Request request, CancellationToken ct)
        {
            var caller = currentUser.RequireUserId();
            if (request.RecipientId == caller)
            {
                throw new ValidationException("dm:recipient:self", "You cannot open a conversation with yourself.");
            }

            var recipientExists = await db.Users.AsNoTracking().AnyAsync(u => u.Id == request.RecipientId, ct);
            if (!recipientExists)
            {
                throw new NotFoundException("user:not_found", "User not found.");
            }

            var (lo, hi) = Normalize(caller, request.RecipientId);
            var result = await writer.Create(lo, hi, ct);
            var conversation = result.Conversation;

            return new Result(
                new Response(conversation.Id, [conversation.UserLo, conversation.UserHi], conversation.CreatedAt),
                result.Created);
        }

        private static (string Lo, string Hi) Normalize(string a, string b) =>
            string.CompareOrdinal(a, b) < 0 ? (a, b) : (b, a);
    }
}
