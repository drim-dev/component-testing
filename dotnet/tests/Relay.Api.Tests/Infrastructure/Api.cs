using System.Net.Http.Json;
using System.Text.Json;

namespace Relay.Api.Tests.Infrastructure;

/// <summary>The pinned error body (spec/02-api.md §0): <c>{ status, code, message }</c>.</summary>
public sealed record ErrorBody(int Status, string Code, string Message);

public static class Api
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public static async Task<ErrorBody> ReadError(this HttpResponseMessage response)
    {
        var body = await response.Content.ReadFromJsonAsync<ErrorBody>(JsonOptions);
        return body ?? throw new InvalidOperationException("Response had no error body.");
    }

    public static async Task<string> ReadRawBody(this HttpResponseMessage response) =>
        await response.Content.ReadAsStringAsync();

    public static async Task<T> ReadJson<T>(this HttpResponseMessage response)
    {
        var body = await response.Content.ReadFromJsonAsync<T>(JsonOptions);
        return body ?? throw new InvalidOperationException($"Response body could not be read as {typeof(T).Name}.");
    }
}
