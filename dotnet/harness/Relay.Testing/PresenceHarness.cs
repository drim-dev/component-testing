using System.Collections.Concurrent;
using Grpc.Core;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Relay.Api.Features.Presence.Grpc;

namespace Relay.Testing;

/// <summary>
/// gRPC presence harness (spec/04-dependencies.md §8). Presence is a NEIGHBOUR service, so in
/// a component test of the Relay API it is STUBBED, not run for real: this harness boots a
/// stub gRPC server that answers from an in-memory online set over a real HTTP/2 socket, so
/// the API still consumes presence through genuine gRPC — the transport-agnostic proof —
/// without dragging the neighbour's own dependencies (its Redis) into the test. It starts
/// uniformly with the other harnesses, owning nothing but a loopback port.
/// <c>SetOnline</c> = program the stub's canned answer; <c>FailStreamAfter</c> = arm the
/// stream to fail after N messages (the deterministic partial-stream probe); <c>Reset</c> =
/// clear the online set and the fault flag.
/// </summary>
public sealed class PresenceHarness<TProgram> : IDependencyHarness<TProgram>
    where TProgram : class
{
    private readonly StubPresenceService _stub = new();
    private WebApplication? _service;
    private string? _address;

    public string Address => _address
        ?? throw new InvalidOperationException("PresenceHarness is not started.");

    public void ConfigureWebHostBuilder(IWebHostBuilder builder) =>
        builder.UseSetting("Presence:Address", Address);

    public async Task Start(WebApplicationFactory<TProgram> factory, CancellationToken cancellationToken)
    {
        var builder = WebApplication.CreateBuilder();
        builder.WebHost.ConfigureKestrel(options =>
            options.Listen(System.Net.IPAddress.Loopback, 0, listen => listen.Protocols = HttpProtocols.Http2));
        builder.Services.AddGrpc();
        builder.Services.AddSingleton(_stub);

        _service = builder.Build();
        _service.MapGrpcService<StubPresenceService>();
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
    }

    /// <summary>Program the stub: mark a user online in its canned answer.</summary>
    public void SetOnline(string userId) => _stub.SetOnline(userId);

    /// <summary>Fault control: the next stream emits this many statuses, then aborts mid-stream.</summary>
    public void FailStreamAfter(int messages) => _stub.FailStreamAfter(messages);

    public void Reset() => _stub.Reset();
}

/// <summary>
/// The stub gRPC presence service: a canned-response stand-in for the neighbour. Answers the
/// unary and streaming RPCs from an in-memory online set, with a test-only fault that aborts
/// the stream after N messages (the deterministic partial-stream probe for G-GRPC). No Redis,
/// no neighbour dependencies — just the contract the Relay API consumes.
/// </summary>
internal sealed class StubPresenceService : Relay.Api.Features.Presence.Grpc.Presence.PresenceBase
{
    private readonly ConcurrentDictionary<string, byte> _online = new();
    private int? _failAfter;

    public void SetOnline(string userId) => _online[userId] = 1;

    public void FailStreamAfter(int messages) => _failAfter = messages;

    public void Reset()
    {
        _online.Clear();
        _failAfter = null;
    }

    public override Task<PresenceStatus> GetPresence(GetPresenceRequest request, ServerCallContext context) =>
        Task.FromResult(new PresenceStatus { UserId = request.UserId, Online = _online.ContainsKey(request.UserId) });

    public override async Task StreamChannelPresence(
        StreamChannelPresenceRequest request,
        IServerStreamWriter<PresenceStatus> responseStream,
        ServerCallContext context)
    {
        var emitted = 0;
        foreach (var userId in request.UserIds)
        {
            if (_failAfter is { } limit && emitted >= limit)
            {
                throw new RpcException(new Status(
                    StatusCode.Unavailable, "presence stream fault (test-only): aborting mid-stream"));
            }

            await responseStream.WriteAsync(new PresenceStatus { UserId = userId, Online = _online.ContainsKey(userId) });
            emitted++;
        }
    }
}
