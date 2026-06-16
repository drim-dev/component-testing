using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using StackExchange.Redis;
using Testcontainers.Redis;

namespace Relay.Testing;

/// <summary>
/// Redis harness (membership cache, unread counters, breaker state). Real container;
/// <c>Seed</c> = write keys directly (e.g. pre-set a counter to prove the reset path);
/// <c>Assert</c> = read keys back; <c>Reset = FLUSHDB</c> — the trivially fast reset,
/// deliberately contrasted with the relational reset (guide §6).
/// </summary>
public sealed class RedisHarness<TProgram> : IDependencyHarness<TProgram>
    where TProgram : class
{
    private RedisContainer? _redis;
    private ConnectionMultiplexer? _connection;

    public string ConnectionString => _redis?.GetConnectionString()
        ?? throw new InvalidOperationException("RedisHarness is not started.");

    public void ConfigureWebHostBuilder(IWebHostBuilder builder) =>
        builder.UseSetting("ConnectionStrings:redis", ConnectionString);

    public async Task Start(WebApplicationFactory<TProgram> factory, CancellationToken cancellationToken)
    {
        _redis = new RedisBuilder(ContainerImages.Redis).Build();
        await _redis.StartAsync(cancellationToken);

        var options = ConfigurationOptions.Parse(ConnectionString);
        options.AllowAdmin = true;
        _connection = await ConnectionMultiplexer.ConnectAsync(options);
    }

    public async Task Stop(CancellationToken cancellationToken)
    {
        if (_connection is not null)
        {
            await _connection.DisposeAsync();
        }

        if (_redis is not null)
        {
            await _redis.StopAsync(cancellationToken);
            await _redis.DisposeAsync();
        }
    }

    public Task SetCounter(string key, long value) =>
        Database.StringSetAsync(key, value);

    public async Task<long?> GetCounter(string key)
    {
        var value = await Database.StringGetAsync(key);
        return value.HasValue ? (long)value : null;
    }

    public Task<bool> KeyExists(string key) => Database.KeyExistsAsync(key);

    public async Task<string[]> SetMembers(string key)
    {
        var members = await Database.SetMembersAsync(key);
        return members.Select(m => (string)m!).ToArray();
    }

    public Task FlushDb() =>
        Connection.GetServers()[0].FlushDatabaseAsync(Database.Database);

    private ConnectionMultiplexer Connection =>
        _connection ?? throw new InvalidOperationException("RedisHarness is not started.");

    private IDatabase Database => Connection.GetDatabase();
}
