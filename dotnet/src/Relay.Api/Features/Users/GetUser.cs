using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Exceptions;
using Relay.Api.Common.Http;
using Relay.Api.Database;

namespace Relay.Api.Features.Users;

public static class GetUser
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapGet("/users/{id}", async (string id, ISender sender, CancellationToken ct) =>
                Results.Ok(await sender.Send(new Request(id), ct)));
        }
    }

    public sealed record Request(string Id) : IRequest<Response>;

    public sealed record Response(string Id, string Handle, string DisplayName, DateTime CreatedAt);

    public sealed class RequestHandler(AppDbContext db) : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            var user = await db.Users.AsNoTracking()
                .Where(u => u.Id == request.Id)
                .Select(u => new Response(u.Id, u.Handle, u.DisplayName, u.CreatedAt))
                .FirstOrDefaultAsync(ct);

            return user ?? throw new NotFoundException("user:not_found", "User not found.");
        }
    }
}
