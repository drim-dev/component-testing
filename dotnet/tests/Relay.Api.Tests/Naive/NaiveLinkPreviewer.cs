using System.Net.Http.Json;
using Microsoft.Extensions.Configuration;
using Relay.Api.Features.Channels;

namespace Relay.Api.Tests.Naive;

/// <summary>
/// G-HTTP naive variant: awaits the unfurl call with NO timeout and NO try-catch. The
/// default shape an agent ships when it has only seen the unfurl service return 200
/// instantly (a mock never hangs and never 500s). A slow upstream makes the post wait the
/// full response time (no 800 ms cap), and an error tears the whole post down — there is
/// no circuit breaker, so every post keeps hammering a dead upstream.
/// </summary>
public sealed class NaiveLinkPreviewer(IConfiguration configuration) : ILinkPreviewer
{
    private static readonly HttpClient Http = new();

    public async Task<string?> TryUnfurl(string text, CancellationToken ct)
    {
        var url = LinkPreviewer.FirstUrl(text);
        if (url is null)
        {
            return null;
        }

        var baseUrl = configuration["Unfurl:BaseUrl"];
        using var response = await Http.GetAsync($"{baseUrl}/unfurl?url={Uri.EscapeDataString(url)}", ct);
        response.EnsureSuccessStatusCode();
        var payload = await response.Content.ReadFromJsonAsync<UnfurlResponse>(ct);
        return payload?.Title;
    }

    private sealed record UnfurlResponse(string? Title);
}
