namespace Relay.Api.Tests.Infrastructure;

/// <summary>
/// Base for every component test: a reset-per-test database and a fresh seeder.
/// Concrete classes carry <c>[Collection(RelayCollection.Name)]</c> so they share the
/// one suite fixture.
/// </summary>
public abstract class RelayTest : IAsyncLifetime
{
    protected RelayTest(TestFixture fixture)
    {
        Fixture = fixture;
        Seed = new Seed(fixture);
    }

    protected TestFixture Fixture { get; }
    protected Seed Seed { get; }

    public Task InitializeAsync() => Fixture.Reset(CancellationToken.None);

    public Task DisposeAsync() => Task.CompletedTask;

    protected static CancellationToken Timeout(int seconds) =>
        new CancellationTokenSource(TimeSpan.FromSeconds(seconds)).Token;
}
