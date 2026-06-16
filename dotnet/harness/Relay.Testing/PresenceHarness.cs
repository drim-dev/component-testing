using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Relay.Api.Features.Presence;
using StackExchange.Redis;

namespace Relay.Testing;

/// <summary>
/// gRPC presence harness (spec/04-dependencies.md §8). Unlike the LLM fake, the presence
/// service is a REAL companion-owned process: this harness boots the actual
/// <see cref="PresenceService"/> on an ephemeral HTTP/2 port over a real socket, so the
/// API consumes it through genuine gRPC — the transport-agnostic proof. It shares the
/// suite's Redis so a <c>POST /me/heartbeat</c> is observable through the stream.
/// <c>Seed</c> = set presence keys directly; <c>fault control</c> = arm the stream to fail
/// after N messages (the deterministic partial-stream probe); <c>Reset</c> = clear
/// presence keys and the fault flag.
/// </summary>
public sealed class PresenceHarness<TProgram> : IDependencyHarness<TProgram>
    where TProgram : class
{
    private readonly PresenceStreamFault _fault = new();
    private WebApplication? _service;
    private ConnectionMultiplexer? _redis;
    private string? _address;

    public string Address => _address
        ?? throw new InvalidOperationException("PresenceHarness is not started.");

    public void ConfigureWebHostBuilder(IWebHostBuilder builder) =>
        builder.UseSetting("Presence:Address", Address);

    /// <summary>
    /// Not used: the presence service needs the suite's Redis connection string, which is
    /// only known after <see cref="RedisHarness{TProgram}"/> starts, so the fixture calls
    /// <see cref="StartService"/> explicitly after Redis. (A harness whose start depends on
    /// another harness — honestly out of the uniform parallel start.)
    /// </summary>
    public Task Start(WebApplicationFactory<TProgram> factory, CancellationToken cancellationToken) =>
        throw new InvalidOperationException(
            "PresenceHarness depends on Redis; start it with StartService(redisConnectionString, ct).");

    public async Task StartService(string redisConnectionString, CancellationToken cancellationToken)
    {
        _redis = await ConnectionMultiplexer.ConnectAsync(redisConnectionString);

        var builder = WebApplication.CreateBuilder();
        builder.WebHost.ConfigureKestrel(options =>
            options.Listen(System.Net.IPAddress.Loopback, 0, listen => listen.Protocols = HttpProtocols.Http2));
        builder.Services.AddGrpc();
        builder.Services.AddSingleton<IConnectionMultiplexer>(_redis);
        builder.Services.AddSingleton(_fault);
        builder.Services.AddScoped<PresenceService>();

        _service = builder.Build();
        _service.MapGrpcService<PresenceService>();
        await _service.StartAsync(cancellationToken);

        _address = _service.Services
            .GetRequiredService<Microsoft.AspNetCore.Hosting.Server.IServer>()
            .Features.Get<Microsoft.AspNetCore.Hosting.Server.Features.IServerAddressesFeature>()!
            .Addresses.First();
    }

    public async Task Stop(CancellationToken cancellationToken)
    {
        if (_service is not null)
        {
            await _service.StopAsync(cancellationToken);
            await _service.DisposeAsync();
        }

        if (_redis is not null)
        {
            await _redis.DisposeAsync();
        }
    }

    /// <summary>Seed: mark a user online directly (same key the heartbeat writes), 60 s TTL.</summary>
    public Task SetOnline(string userId) =>
        Redis.GetDatabase().StringSetAsync(PresenceService.KeyPrefix + userId, "1", Heartbeat.Ttl);

    /// <summary>Fault control: the next stream emits this many statuses, then aborts mid-stream.</summary>
    public void FailStreamAfter(int messages) => _fault.FailAfter(messages);

    public void Reset()
    {
        _fault.Clear();
        // Presence keys live in the shared Redis; the fixture's FLUSHDB clears them.
    }

    private ConnectionMultiplexer Redis =>
        _redis ?? throw new InvalidOperationException("PresenceHarness is not started.");
}
