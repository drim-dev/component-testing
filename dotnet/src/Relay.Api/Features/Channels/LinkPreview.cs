using System.Text.RegularExpressions;
using Microsoft.Extensions.Configuration;
using StackExchange.Redis;

namespace Relay.Api.Features.Channels;

/// <summary>
/// Link unfurl on message post (the G-HTTP seam). The correct implementation calls the
/// external unfurl service through a REAL HTTP client with an 800 ms timeout and degrades
/// gracefully (timeout / 5xx / network error → null preview, message still posts), and a
/// circuit breaker opens after 5 consecutive failures for 30 s. The naive variant awaits
/// the call with no timeout and no try-catch, so a slow or failing upstream makes the
/// whole post hang or 500.
/// </summary>
public interface ILinkPreviewer
{
    Task<string?> TryUnfurl(string text, CancellationToken ct);
}

public sealed partial class LinkPreviewer(HttpClient http, IConnectionMultiplexer redis, IConfiguration configuration)
    : ILinkPreviewer
{
    public const int FailureThreshold = 5;
    public static readonly TimeSpan BreakerWindow = TimeSpan.FromSeconds(30);
    public static readonly TimeSpan Timeout = TimeSpan.FromMilliseconds(800);

    private const string FailuresKey = "unfurl:breaker:failures";
    private const string OpenUntilKey = "unfurl:breaker:open_until";

    public async Task<string?> TryUnfurl(string text, CancellationToken ct)
    {
        var url = FirstUrl(text);
        if (url is null || await BreakerOpen())
        {
            return null;
        }

        try
        {
            var title = await Fetch(url, ct);
            await RecordSuccess();
            return title;
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or OperationCanceledException)
        {
            await RecordFailure();
            return null;
        }
    }

    private async Task<string?> Fetch(string url, CancellationToken ct)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(Timeout);

        var baseUrl = configuration["Unfurl:BaseUrl"];
        using var response = await http.GetAsync($"{baseUrl}/unfurl?url={Uri.EscapeDataString(url)}", timeout.Token);
        if (!response.IsSuccessStatusCode)
        {
            throw new HttpRequestException($"Unfurl upstream returned {(int)response.StatusCode}.");
        }

        var payload = await response.Content.ReadFromJsonAsync<UnfurlResponse>(timeout.Token);
        return payload?.Title;
    }

    private async Task<bool> BreakerOpen()
    {
        var openUntil = await redis.GetDatabase().StringGetAsync(OpenUntilKey);
        if (!openUntil.HasValue)
        {
            return false;
        }

        return DateTimeOffset.FromUnixTimeMilliseconds((long)openUntil) > DateTimeOffset.UtcNow;
    }

    private Task<bool> RecordSuccess() => redis.GetDatabase().KeyDeleteAsync(FailuresKey);

    private async Task RecordFailure()
    {
        var failures = await redis.GetDatabase().StringIncrementAsync(FailuresKey);
        if (failures >= FailureThreshold)
        {
            var openUntil = DateTimeOffset.UtcNow.Add(BreakerWindow).ToUnixTimeMilliseconds();
            await redis.GetDatabase().StringSetAsync(OpenUntilKey, openUntil, BreakerWindow);
        }
    }

    internal static string? FirstUrl(string text)
    {
        var match = UrlPattern().Match(text);
        return match.Success ? match.Value : null;
    }

    private sealed record UnfurlResponse(string? Title);

    [GeneratedRegex(@"https?://[^\s]+")]
    private static partial Regex UrlPattern();
}
