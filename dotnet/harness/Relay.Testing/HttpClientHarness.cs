using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;

namespace Relay.Testing;

/// <summary>
/// Drives the app through its real HTTP boundary. Identity is the trusted
/// <c>X-User-Id</c> header (the companion's auth model), so a client is "logged in as"
/// a user simply by carrying that header.
/// </summary>
public sealed class HttpClientHarness<TProgram> : IDependencyHarness<TProgram>
    where TProgram : class
{
    private WebApplicationFactory<TProgram>? _factory;

    public void ConfigureWebHostBuilder(IWebHostBuilder builder)
    {
        // No host configuration; this harness only constructs clients.
    }

    public Task Start(WebApplicationFactory<TProgram> factory, CancellationToken cancellationToken)
    {
        _factory = factory;
        return Task.CompletedTask;
    }

    public Task Stop(CancellationToken cancellationToken) => Task.CompletedTask;

    /// <summary>An anonymous client (no identity header) — for the 401 identity scenarios.</summary>
    public HttpClient CreateClient() =>
        (_factory ?? throw new InvalidOperationException("HttpClientHarness is not started.")).CreateClient();

    /// <summary>A client authenticated as <paramref name="userId"/> via the trusted header.</summary>
    public HttpClient CreateClient(string userId)
    {
        var client = CreateClient();
        client.DefaultRequestHeaders.Add("X-User-Id", userId);
        return client;
    }
}
