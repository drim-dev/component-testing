using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Relay.Testing;

/// <summary>
/// Outbound-HTTP unfurl harness (spec/04-dependencies.md §7): a REAL local stub HTTP
/// server with fault injection — NOT an in-process mock of the client class. The timeout,
/// the socket and the status codes are real, which is the whole point: resilience lives
/// in the real client config + socket behavior, invisible to a mock. <c>Seed</c> = program
/// the route (200+title / delay &gt; timeout / 500); <c>Assert</c> = received-request count
/// (the circuit-breaker proof); <c>Reset</c> = clear the programmed route + counter.
/// </summary>
public sealed class UnfurlHarness<TProgram> : IDependencyHarness<TProgram>
    where TProgram : class
{
    private readonly Lock _lock = new();
    private WebApplication? _stub;
    private string? _baseUrl;
    private Behavior _behavior = Behavior.Ok("Example");
    private int _requestCount;

    public string BaseUrl => _baseUrl ?? throw new InvalidOperationException("UnfurlHarness is not started.");

    public int RequestCount
    {
        get { lock (_lock) { return _requestCount; } }
    }

    public void ConfigureWebHostBuilder(IWebHostBuilder builder) =>
        builder.UseSetting("Unfurl:BaseUrl", BaseUrl);

    public async Task Start(WebApplicationFactory<TProgram> factory, CancellationToken cancellationToken)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.UseUrls("http://127.0.0.1:0");
        builder.Logging.ClearProviders();
        _stub = builder.Build();

        _stub.MapGet("/unfurl", async (HttpContext context) =>
        {
            Behavior behavior;
            lock (_lock)
            {
                _requestCount++;
                behavior = _behavior;
            }

            await behavior.Apply(context);
        });

        await _stub.StartAsync(cancellationToken);
        _baseUrl = _stub.Urls.First();
    }

    public async Task Stop(CancellationToken cancellationToken)
    {
        if (_stub is not null)
        {
            await _stub.StopAsync(cancellationToken);
            await _stub.DisposeAsync();
        }
    }

    public void ProgramOk(string title) => SetBehavior(Behavior.Ok(title));

    public void ProgramDelay(TimeSpan delay, string title = "Example") => SetBehavior(Behavior.Delayed(delay, title));

    public void ProgramServerError() => SetBehavior(Behavior.ServerError());

    public void Reset()
    {
        lock (_lock)
        {
            _behavior = Behavior.Ok("Example");
            _requestCount = 0;
        }
    }

    private void SetBehavior(Behavior behavior)
    {
        lock (_lock)
        {
            _behavior = behavior;
        }
    }

    private sealed record Behavior(int Status, string? Title, TimeSpan Delay)
    {
        public static Behavior Ok(string title) => new(200, title, TimeSpan.Zero);

        public static Behavior Delayed(TimeSpan delay, string title) => new(200, title, delay);

        public static Behavior ServerError() => new(500, null, TimeSpan.Zero);

        public async Task Apply(HttpContext context)
        {
            if (Delay > TimeSpan.Zero)
            {
                await Task.Delay(Delay, context.RequestAborted);
            }

            context.Response.StatusCode = Status;
            if (Status == 200)
            {
                await context.Response.WriteAsJsonAsync(new { title = Title });
            }
        }
    }
}
