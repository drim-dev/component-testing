namespace Relay.Api.Common.Auth;

/// <summary>
/// The authenticated caller for the current request, resolved from the trusted
/// <c>X-User-Id</c> header (companion identity model — no OAuth). Populated by
/// <see cref="UserContextMiddleware"/>; handlers read <see cref="UserId"/>.
/// </summary>
public sealed class CurrentUser
{
    public string? UserId { get; private set; }

    public bool IsAuthenticated => UserId is not null;

    public string RequireUserId() =>
        UserId ?? throw new InvalidOperationException("Request reached a handler without an authenticated user.");

    internal void Set(string userId) => UserId = userId;
}
