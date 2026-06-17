using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Relay.Api.Common.Identity;
using Relay.Api.Database;
using Relay.Testing;

namespace Relay.Api.Tests.Infrastructure;

/// <summary>
/// Composition of all dependency harnesses for the suite (one Docker host, one suite).
/// Milestone: PostgreSQL-backed domains (Users, DMs, Channels). Redis/brokers/S3/LLM/
/// HTTP/gRPC harnesses attach here as they land.
/// </summary>
public sealed class TestFixture : IAsyncLifetime
{
    private readonly WebApplicationFactory<Program> _factory;

    public TestFixture()
    {
        Database = new DatabaseHarness<Program, AppDbContext>("relay");
        Redis = new RedisHarness<Program>();
        Kafka = new KafkaHarness<Program>();
        Rabbit = new RabbitMqHarness<Program>();
        S3 = new S3Harness<Program>();
        Llm = new LlmHarness<Program>();
        Unfurl = new UnfurlHarness<Program>();
        Presence = new PresenceHarness<Program>();
        Http = new HttpClientHarness<Program>();

        _factory = new WebApplicationFactory<Program>()
            .AddHarness(Database)
            .AddHarness(Redis)
            .AddHarness(Kafka)
            .AddHarness(Rabbit)
            .AddHarness(S3)
            .AddHarness(Llm)
            .AddHarness(Unfurl)
            .AddHarness(Presence)
            .AddHarness(Http);
    }

    public DatabaseHarness<Program, AppDbContext> Database { get; }
    public RedisHarness<Program> Redis { get; }
    public KafkaHarness<Program> Kafka { get; }
    public RabbitMqHarness<Program> Rabbit { get; }
    public S3Harness<Program> S3 { get; }
    public LlmHarness<Program> Llm { get; }
    public UnfurlHarness<Program> Unfurl { get; }
    public PresenceHarness<Program> Presence { get; }
    public HttpClientHarness<Program> Http { get; }

    /// <summary>Mints opaque, time-ordered ids for seeding — the same generator the app uses (id 0).</summary>
    public IdFactory Ids => _factory.Services.GetRequiredService<IdFactory>();

    /// <summary>
    /// A client whose app has ONE seam replaced by a naive variant — the §11.D injection
    /// mechanism. The naive host runs on a distinct IdGen generator (id 1) so its ids
    /// never collide with seeded data minted from the default generator (id 0), and with
    /// its worker role OFF — a naive host must not consume the suite's real topics/queues.
    /// The swap is scoped to the returned client; the shared suite stays correct.
    /// </summary>
    public HttpClient NaiveClient<TService, TNaive>(string userId, params (string Key, string Value)[] settings)
        where TService : class
        where TNaive : class, TService
    {
        var derived = DeriveNaiveFactory<TService, TNaive>([("Workers:Enabled", "false"), .. settings]);
        var client = derived.CreateClient();
        client.DefaultRequestHeaders.Add("X-User-Id", userId);
        return client;
    }

    /// <summary>
    /// A naive host whose WORKERS run too — for consumer-side naive demos (G-KAFKA
    /// consumer, G-RABBIT). All broker routing is pointed at the parallel naive
    /// topic/group/queue so its buggy processing never races the suite's correct
    /// consumers, and it MUST be disposed at the end of the demo (stopping its
    /// consumers), hence the IAsyncDisposable handle.
    /// </summary>
    public NaiveWorkerHost NaiveWorkers<TService, TNaive>(params (string Key, string Value)[] settings)
        where TService : class
        where TNaive : class, TService
    {
        var derived = DeriveNaiveFactory<TService, TNaive>(
        [
            ("Kafka:Topic", KafkaHarness<Program>.NaiveTopic),
            ("Kafka:GroupId", KafkaHarness<Program>.NaiveGroupId),
            ("Rabbit:Queue", RabbitMqHarness<Program>.NaiveQueue),
            .. settings,
        ]);
        _ = derived.Server;
        return new NaiveWorkerHost(derived);
    }

    private WebApplicationFactory<Program> DeriveNaiveFactory<TService, TNaive>(
        (string Key, string Value)[] settings)
        where TService : class
        where TNaive : class, TService =>
        _factory.WithWebHostBuilder(builder =>
        {
            builder.UseSetting("IdGen:GeneratorId", "1");
            foreach (var (key, value) in settings)
            {
                builder.UseSetting(key, value);
            }

            builder.ConfigureServices(services =>
            {
                services.RemoveAll<TService>();
                services.AddScoped<TService, TNaive>();
            });
        });

    public async Task Reset(CancellationToken ct)
    {
        // Drain both broker pipelines BEFORE truncating: an event or job processed after
        // the wipe would write into the next test's clean state.
        await Kafka.AwaitConsumed(Timeout(30));
        await Rabbit.Drain(Timeout(30));
        await Database.Reset(ct);
        await Redis.FlushDb();
        await S3.DeleteAllObjects(Timeout(30));
        Llm.Reset();
        Unfurl.Reset();
        Presence.Reset();
    }

    public async Task InitializeAsync()
    {
        await Task.WhenAll(
            Database.Start(_factory, Timeout(120)),
            Redis.Start(_factory, Timeout(120)),
            Kafka.Start(_factory, Timeout(180)),
            Rabbit.Start(_factory, Timeout(180)),
            S3.Start(_factory, Timeout(120)),
            Unfurl.Start(_factory, Timeout(30)),
            Presence.Start(_factory, Timeout(30)));
        await Http.Start(_factory, Timeout(30));
        await Database.CreateSchema(Timeout(60));
        _ = _factory.Server;
    }

    public async Task DisposeAsync()
    {
        await Http.Stop(Timeout(30));
        await _factory.DisposeAsync();
        await Presence.Stop(Timeout(30));
        await Unfurl.Stop(Timeout(30));
        await Kafka.Stop(Timeout(60));
        await Rabbit.Stop(Timeout(60));
        await S3.Stop(Timeout(30));
        await Redis.Stop(Timeout(30));
        await Database.Stop(Timeout(30));
    }

    private static CancellationToken Timeout(int seconds) =>
        new CancellationTokenSource(TimeSpan.FromSeconds(seconds)).Token;
}

/// <summary>A disposable handle for a naive host that runs background consumers.</summary>
public sealed class NaiveWorkerHost(WebApplicationFactory<Program> factory) : IAsyncDisposable
{
    public ValueTask DisposeAsync() => factory.DisposeAsync();
}
