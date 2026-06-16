using MediatR;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Routing;
using Relay.Api.Common.Exceptions;
using Relay.Api.Common.Http;
using ValidationException = Relay.Api.Common.Exceptions.ValidationException;

namespace Relay.Api.Features.Channels;

/// <summary>
/// The synchronous unfurl proxy — the only outbound-HTTP critical path (spec/02-api.md §6).
/// Unlike the post-time unfurl, an upstream failure here surfaces as 502 (the caller asked
/// for the title directly), not graceful degradation.
/// </summary>
public static class GetLinkPreview
{
    public sealed class Endpoint : IEndpoint
    {
        public void MapEndpoint(IEndpointRouteBuilder app)
        {
            app.MapGet("/links/preview", async (string? url, ISender sender, CancellationToken ct) =>
                Results.Ok(await sender.Send(new Request(url), ct)));
        }
    }

    public sealed record Request(string? Url) : IRequest<Response>;

    public sealed record Response(string Title);

    public sealed class RequestHandler(ILinkPreviewer previewer) : IRequestHandler<Request, Response>
    {
        public async Task<Response> Handle(Request request, CancellationToken ct)
        {
            if (string.IsNullOrWhiteSpace(request.Url) || LinkPreviewer.FirstUrl(request.Url) is null)
            {
                throw new ValidationException("unfurl:url:invalid", "A valid http(s) url is required.");
            }

            var title = await previewer.TryUnfurl(request.Url, ct);
            if (title is null)
            {
                throw new UpstreamException("unfurl:upstream_failed", "The unfurl upstream failed.");
            }

            return new Response(title);
        }
    }
}
