using System.Text.Json;
using Microsoft.AspNetCore.Mvc.Testing;

namespace Relay.Testing;

public static class HarnessExtensions
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public static WebApplicationFactory<T> AddHarness<T>(
        this WebApplicationFactory<T> factory,
        IDependencyHarness<T> harness)
        where T : class =>
        factory.WithWebHostBuilder(harness.ConfigureWebHostBuilder);

    /// <summary>Deserialize a response body with the API's JSON conventions (camelCase, web defaults).</summary>
    public static async Task<T?> ReadJson<T>(this HttpContent content, CancellationToken ct = default)
    {
        var json = await content.ReadAsStringAsync(ct);
        return JsonSerializer.Deserialize<T>(json, JsonOptions);
    }
}
