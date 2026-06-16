using Microsoft.AspNetCore.Http;
using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Exceptions;
using Relay.Api.Database;

namespace Relay.Api.Common.Auth;

/// <summary>
/// Resolves identity from the trusted <c>X-User-Id</c> header on every request except
/// <c>POST /users</c> (the only unauthenticated route). Missing header → 401
/// <c>identity:missing</c>; header that names no existing user → 401 <c>identity:unknown</c>.
/// </summary>
public sealed class UserContextMiddleware(RequestDelegate next)
{
    public async Task InvokeAsync(HttpContext context, CurrentUser currentUser, AppDbContext db)
    {
        if (IsBootstrapRoute(context.Request))
        {
            await next(context);
            return;
        }

        if (!context.Request.Headers.TryGetValue("X-User-Id", out var header) || string.IsNullOrWhiteSpace(header))
        {
            throw new UnauthorizedException("identity:missing", "The X-User-Id header is required.");
        }

        var userId = header.ToString();
        var exists = await db.Users.AsNoTracking().AnyAsync(u => u.Id == userId, context.RequestAborted);
        if (!exists)
        {
            throw new UnauthorizedException("identity:unknown", "The X-User-Id header names no known user.");
        }

        currentUser.Set(userId);
        await next(context);
    }

    private static bool IsBootstrapRoute(HttpRequest request) =>
        HttpMethods.IsPost(request.Method) && request.Path.Equals("/users", StringComparison.Ordinal);
}
