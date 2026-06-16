using System.Collections;
using System.Linq.Expressions;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Npgsql;
using Respawn;
using Testcontainers.PostgreSql;

namespace Relay.Testing;

/// <summary>
/// PostgreSQL harness (the system of record). Real container, schema from the EF model,
/// fast reset via Respawn. Also installs the deterministic one-shot fault used by the
/// G-TX catching test (a trigger that raises on the second <c>dm_participants</c> insert).
/// </summary>
public sealed class DatabaseHarness<TProgram, TDbContext> : IDependencyHarness<TProgram>
    where TProgram : class
    where TDbContext : DbContext
{
    private readonly string _connectionStringName;
    private PostgreSqlContainer? _postgres;
    private WebApplicationFactory<TProgram>? _factory;
    private Respawner? _respawner;

    public DatabaseHarness(string connectionStringName) => _connectionStringName = connectionStringName;

    public string ConnectionString => _postgres?.GetConnectionString()
        ?? throw new InvalidOperationException("DatabaseHarness is not started.");

    public void ConfigureWebHostBuilder(IWebHostBuilder builder) =>
        builder.UseSetting($"ConnectionStrings:{_connectionStringName}", ConnectionString);

    public async Task Start(WebApplicationFactory<TProgram> factory, CancellationToken cancellationToken)
    {
        _factory = factory;
        _postgres = new PostgreSqlBuilder(ContainerImages.PostgreSql).Build();
        await _postgres.StartAsync(cancellationToken);
    }

    public async Task Stop(CancellationToken cancellationToken)
    {
        if (_postgres is not null)
        {
            await _postgres.StopAsync(cancellationToken);
            await _postgres.DisposeAsync();
        }
    }

    public async Task CreateSchema(CancellationToken cancellationToken)
    {
        await using var scope = _factory!.Services.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<TDbContext>();
        await db.Database.EnsureCreatedAsync(cancellationToken);
        await InstallTxFault(cancellationToken);
    }

    public async Task Reset(CancellationToken cancellationToken)
    {
        await using var connection = new NpgsqlConnection(ConnectionString);
        await connection.OpenAsync(cancellationToken);

        _respawner ??= await Respawner.CreateAsync(connection, new RespawnerOptions
        {
            SchemasToInclude = ["public"],
            DbAdapter = DbAdapter.Postgres,
        });

        await _respawner.ResetAsync(connection);
    }

    public async Task Save(params object[] entities)
    {
        await using var scope = _factory!.Services.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<TDbContext>();

        foreach (var collection in entities.OfType<IEnumerable>())
        {
            db.AddRange(collection.Cast<object>());
        }

        db.AddRange(entities.Where(e => e is not IEnumerable));
        await db.SaveChangesAsync();
    }

    public async Task Execute(Func<TDbContext, Task> action)
    {
        await using var scope = _factory!.Services.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<TDbContext>();
        await action(db);
    }

    public async Task<TResult> Execute<TResult>(Func<TDbContext, Task<TResult>> action)
    {
        await using var scope = _factory!.Services.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<TDbContext>();
        return await action(db);
    }

    public async Task<int> Count<TEntity>(Expression<Func<TEntity, bool>> predicate)
        where TEntity : class
    {
        await using var scope = _factory!.Services.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<TDbContext>();
        return await db.Set<TEntity>().CountAsync(predicate);
    }

    public async Task<TEntity?> SingleOrDefault<TEntity>(Expression<Func<TEntity, bool>> predicate)
        where TEntity : class
    {
        await using var scope = _factory!.Services.CreateAsyncScope();
        var db = scope.ServiceProvider.GetRequiredService<TDbContext>();
        return await db.Set<TEntity>().AsNoTracking().SingleOrDefaultAsync(predicate);
    }

    /// <summary>
    /// Arms the deterministic mid-transaction failure for the G-TX case: the next time a
    /// transaction reaches its SECOND <c>dm_participants</c> insert, the database raises.
    /// The correct (transactional) writer rolls everything back; a naive non-transactional
    /// writer leaves an orphan conversation behind — which the catching test reads.
    /// </summary>
    public async Task ArmParticipantInsertFault(CancellationToken cancellationToken)
    {
        await using var connection = new NpgsqlConnection(ConnectionString);
        await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText = "INSERT INTO _tx_fault (id, remaining) VALUES (1, 2) " +
                              "ON CONFLICT (id) DO UPDATE SET remaining = 2;";
        await command.ExecuteNonQueryAsync(cancellationToken);
    }

    private async Task InstallTxFault(CancellationToken cancellationToken)
    {
        await using var connection = new NpgsqlConnection(ConnectionString);
        await connection.OpenAsync(cancellationToken);
        await using var command = connection.CreateCommand();
        command.CommandText = """
            CREATE TABLE IF NOT EXISTS _tx_fault (id int PRIMARY KEY, remaining int NOT NULL);

            CREATE OR REPLACE FUNCTION _tx_fault_raise() RETURNS trigger AS $$
            DECLARE left_count int;
            BEGIN
                UPDATE _tx_fault SET remaining = remaining - 1 WHERE id = 1 RETURNING remaining INTO left_count;
                IF left_count IS NOT NULL AND left_count = 0 THEN
                    RAISE EXCEPTION 'tx fault injected on participant insert';
                END IF;
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;

            DROP TRIGGER IF EXISTS _tx_fault_trg ON dm_participants;
            CREATE TRIGGER _tx_fault_trg BEFORE INSERT ON dm_participants
                FOR EACH ROW EXECUTE FUNCTION _tx_fault_raise();
            """;
        await command.ExecuteNonQueryAsync(cancellationToken);
    }
}
