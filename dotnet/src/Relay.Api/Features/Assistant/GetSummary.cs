using FluentValidation;
using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Http;
using Relay.Api.Database;
using Relay.Api.Domain.Channels;
using Relay.Api.Features.Channels;
using ValidationException = Relay.Api.Common.Exceptions.ValidationException;

namespace Relay.Api.Features.Assistant;

public static class GetSummary
{
    public const int DefaultMessageLimit = 50;

    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapPost("/channels/{id}/summary", async (string id, Body? body, ISender sender, CancellationToken ct) =>
                Results.Ok(await sender.Send(new Request(id, body?.MessageLimit), ct)));
        }

        public sealed record Body(int? MessageLimit);
    }

    public sealed record Request(string ChannelId, int? MessageLimit) : IRequest<Response>;

    public sealed record Response(string Summary);

    public sealed class RequestValidator : AbstractValidator<Request>
    {
        public RequestValidator()
        {
            RuleFor(x => x.MessageLimit)
                .Must(limit => limit is null or (>= 1 and <= 200))
                .WithErrorCode("summary:message_limit:out_of_range")
                .WithMessage("messageLimit must be between 1 and 200.");
        }
    }

    public sealed class RequestHandler(
        CurrentUser currentUser,
        IChannelRoleGate roleGate,
        ISummarizer summarizer,
        AppDbContext db)
        : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            var caller = currentUser.RequireUserId();
            await roleGate.Authorize(request.ChannelId, caller, ChannelRole.Member, ct);

            var limit = request.MessageLimit ?? DefaultMessageLimit;
            var newest = await db.ChannelMessages.AsNoTracking()
                .Where(m => m.ChannelId == request.ChannelId)
                .OrderByDescending(m => m.Id)
                .Take(limit)
                .Join(
                    db.Users.AsNoTracking(),
                    m => m.SenderId,
                    u => u.Id,
                    (m, u) => new { m.Id, u.Handle, m.Text })
                .ToListAsync(ct);

            if (newest.Count == 0)
            {
                throw new ValidationException("summary:no_messages", "There is nothing to summarize.");
            }

            var sources = newest
                .OrderBy(m => m.Id, StringComparer.Ordinal)
                .Select(m => new SummarySource(m.Handle, m.Text))
                .ToList();
            var summary = await summarizer.Summarize(sources, ct);
            return new Response(summary);
        }
    }
}
