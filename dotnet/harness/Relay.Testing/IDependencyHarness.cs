using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;

namespace Relay.Testing;

/// <summary>
/// One dependency's test harness — the reusable "brick" the guide's §5 dissects.
/// Every dependency (real or fake) answers the same five questions; HOW it answers
/// them is the per-dependency shape (spec/04-dependencies.md §0.1):
/// start, wire the seam, seed, assert, reset.
/// </summary>
/// <remarks>
/// Renamed from the drim.dev origin's <c>IHarness&lt;T&gt;</c> to
/// <c>IDependencyHarness&lt;T&gt;</c> (locked, design §11.J): bare "Harness" collides
/// with "test harness = the whole rig". The concrete bricks are
/// <c>DatabaseHarness</c>, <c>RedisHarness</c>, … so the §5/§6 prose listings match the code.
/// </remarks>
public interface IDependencyHarness<T> where T : class
{
    /// <summary>Point the app under test at this dependency (connection string / DI seam).</summary>
    void ConfigureWebHostBuilder(IWebHostBuilder builder);

    /// <summary>Bring up the real dependency (Testcontainer) or construct the fake.</summary>
    Task Start(WebApplicationFactory<T> factory, CancellationToken cancellationToken);

    /// <summary>Tear the dependency down at the end of the suite.</summary>
    Task Stop(CancellationToken cancellationToken);
}
