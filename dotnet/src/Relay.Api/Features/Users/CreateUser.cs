using FluentValidation;
using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Exceptions;
using Relay.Api.Common.Http;
using Relay.Api.Common.Identity;
using Relay.Api.Database;
using Relay.Api.Domain.Users;

namespace Relay.Api.Features.Users;

public static class CreateUser
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapPost("/users", async (Body body, ISender sender, CancellationToken ct) =>
            {
                var response = await sender.Send(new Request(body.Handle, body.DisplayName), ct);
                return Results.Created($"/users/{response.Id}", response);
            });
        }

        public sealed record Body(string Handle, string DisplayName);
    }

    public sealed record Request(string Handle, string DisplayName) : IRequest<Response>;

    public sealed record Response(string Id, string Handle, string DisplayName, DateTime CreatedAt);

    public sealed class RequestValidator : AbstractValidator<Request>
    {
        public RequestValidator()
        {
            RuleFor(x => x.Handle)
                .Matches("^[a-z0-9_]{3,32}$")
                .WithErrorCode("user:handle:invalid")
                .WithMessage("handle must be 3-32 characters of [a-z0-9_].");

            RuleFor(x => x.DisplayName)
                .NotEmpty().WithErrorCode("user:display_name:invalid")
                .WithMessage("displayName must be 1-64 characters.")
                .MaximumLength(64).WithErrorCode("user:display_name:invalid")
                .WithMessage("displayName must be 1-64 characters.");
        }
    }

    public sealed class RequestHandler(AppDbContext db, IdFactory ids) : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            var handleTaken = await db.Users.AsNoTracking().AnyAsync(u => u.Handle == request.Handle, ct);
            if (handleTaken)
            {
                throw new ConflictException("user:handle:taken", "This handle is already taken.");
            }

            var user = new User
            {
                Id = ids.Create(),
                Handle = request.Handle,
                DisplayName = request.DisplayName,
                CreatedAt = DateTime.UtcNow,
            };
            db.Users.Add(user);
            await db.SaveChangesAsync(ct);

            return new Response(user.Id, user.Handle, user.DisplayName, user.CreatedAt);
        }
    }
}
