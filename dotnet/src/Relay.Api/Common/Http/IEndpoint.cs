using Microsoft.AspNetCore.Routing;

namespace Relay.Api.Common.Http;

/// <summary>
/// A vertical slice's HTTP surface. Each feature registers its own route(s).
/// </summary>
public interface IEndpoint
{
    void MapEndpoint(IEndpointRouteBuilder app);
}
