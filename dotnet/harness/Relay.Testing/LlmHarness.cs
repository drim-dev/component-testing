using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Relay.Api.Features.Assistant;

namespace Relay.Testing;

/// <summary>
/// LLM harness — the canonical FAKE (spec/04-dependencies.md §6): nondeterministic, paid
/// and external, so the boundary is a deliberate in-process double, not a container.
/// <c>Seed</c> = program the next response (canned / empty / oversized);
/// <c>Assert</c> = interaction verification — the captured request is where the prompt-
/// injection catch lives; <c>Reset</c> = clear programmed responses and captured calls.
/// Hand-rolled on purpose (no mocking framework) so the pattern reads cross-language.
/// </summary>
public sealed class LlmHarness<TProgram> : IDependencyHarness<TProgram>
    where TProgram : class
{
    private readonly FakeSummaryModel _fake = new();

    public void ConfigureWebHostBuilder(IWebHostBuilder builder) =>
        builder.ConfigureServices(services =>
        {
            services.RemoveAll<ISummaryModel>();
            services.AddSingleton<ISummaryModel>(_fake);
        });

    public Task Start(WebApplicationFactory<TProgram> factory, CancellationToken cancellationToken) =>
        Task.CompletedTask;

    public Task Stop(CancellationToken cancellationToken) => Task.CompletedTask;

    public void ProgramResponse(string response) => _fake.ProgramResponse(response);

    public IReadOnlyList<SummaryModelRequest> CapturedRequests => _fake.CapturedRequests;

    public void Reset() => _fake.Clear();
}

public sealed class FakeSummaryModel : ISummaryModel
{
    private readonly Lock _lock = new();
    private readonly List<SummaryModelRequest> _captured = [];
    private readonly Queue<string> _programmed = new();

    public Task<string> Complete(SummaryModelRequest request, CancellationToken ct)
    {
        lock (_lock)
        {
            _captured.Add(request);
            return Task.FromResult(_programmed.TryDequeue(out var response) ? response : "(canned summary)");
        }
    }

    public IReadOnlyList<SummaryModelRequest> CapturedRequests
    {
        get
        {
            lock (_lock)
            {
                return [.. _captured];
            }
        }
    }

    public void ProgramResponse(string response)
    {
        lock (_lock)
        {
            _programmed.Enqueue(response);
        }
    }

    public void Clear()
    {
        lock (_lock)
        {
            _captured.Clear();
            _programmed.Clear();
        }
    }
}
