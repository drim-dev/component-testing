using FluentValidation;
using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Relay.Api.Common.Auth;
using Relay.Api.Common.Http;
using Relay.Api.Common.Identity;
using Relay.Api.Database;
using Relay.Api.Domain.Channels;

namespace Relay.Api.Features.Channels;

public static class CreateChannel
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapPost("/channels", async (Body body, ISender sender, CancellationToken ct) =>
            {
                var response = await sender.Send(new Request(body.Name, body.Private), ct);
                return Results.Created($"/channels/{response.Id}", response);
            });
        }

        public sealed record Body(string Name, bool Private);
    }

    public sealed record Request(string Name, bool Private) : IRequest<Response>;

    public sealed record Response(string Id, string Name, bool Private, DateTime CreatedAt);

    public sealed class RequestValidator : AbstractValidator<Request>
    {
        public RequestValidator()
        {
            RuleFor(x => x.Name)
                .NotEmpty().WithErrorCode("channel:name:invalid")
                .WithMessage("name must be 1-100 characters.")
                .MaximumLength(100).WithErrorCode("channel:name:invalid")
                .WithMessage("name must be 1-100 characters.");
        }
    }

    public sealed class RequestHandler(AppDbContext db, CurrentUser currentUser, IdFactory ids)
        : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            var now = DateTime.UtcNow;
            var channel = new Channel
            {
                Id = ids.Create(),
                Name = request.Name,
                Private = request.Private,
                CreatedAt = now,
            };
            db.Channels.Add(channel);
            db.ChannelMembers.Add(new ChannelMember
            {
                ChannelId = channel.Id,
                UserId = currentUser.RequireUserId(),
                Role = ChannelRole.Owner,
                JoinedAt = now,
            });

            await db.SaveChangesAsync(ct);

            return new Response(channel.Id, channel.Name, channel.Private, channel.CreatedAt);
        }
    }
}
