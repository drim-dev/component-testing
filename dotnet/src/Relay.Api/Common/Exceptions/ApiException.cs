namespace Relay.Api.Common.Exceptions;

/// <summary>
/// Base for every error Relay deliberately raises. Carries the HTTP status and the
/// pinned <c>area:entity:reason</c> code asserted by the acceptance catalog.
/// </summary>
public abstract class ApiException(int status, string code, string message) : Exception(message)
{
    public int Status { get; } = status;
    public string Code { get; } = code;
}

/// <summary>401 — identity missing or unknown (the only identity failures).</summary>
public sealed class UnauthorizedException(string code, string message)
    : ApiException(401, code, message);

/// <summary>403 — the caller can see the resource but lacks the membership/role.</summary>
public sealed class ForbiddenException(string code, string message)
    : ApiException(403, code, message);

/// <summary>404 — resource absent OR existence hidden from an unauthorized caller.</summary>
public sealed class NotFoundException(string code, string message)
    : ApiException(404, code, message);

/// <summary>409 — state conflict (duplicate handle, already a member, owner cannot leave).</summary>
public sealed class ConflictException(string code, string message)
    : ApiException(409, code, message);

/// <summary>413 — payload too large (attachment over the size limit).</summary>
public sealed class PayloadTooLargeException(string code, string message)
    : ApiException(413, code, message);

/// <summary>422 — input failed validation or a business rule (no silent clamping).</summary>
public sealed class ValidationException(string code, string message)
    : ApiException(422, code, message);

/// <summary>502 — an upstream (model / unfurl / presence stream) violated its contract.</summary>
public sealed class UpstreamException(string code, string message)
    : ApiException(502, code, message);

/// <summary>503 — required infrastructure (the event broker) is unavailable.</summary>
public sealed class InfrastructureUnavailableException(string code, string message)
    : ApiException(503, code, message);
