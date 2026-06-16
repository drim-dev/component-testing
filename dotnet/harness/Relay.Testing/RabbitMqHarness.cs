using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using RabbitMQ.Client;
using Relay.Api.Features.Notifications;
using Testcontainers.RabbitMq;

namespace Relay.Testing;

/// <summary>
/// RabbitMQ harness (queues / acks / DLQ — deliberately different semantics from
/// Kafka's log/offsets). <c>Seed</c> = publish a job directly, including duplicates and
/// poison; <c>Assert</c> = await-until on queue stats via the management API (ready AND
/// unacknowledged — passive declare alone cannot see an in-flight delivery, and a
/// nack-requeue cycle would look "empty" at the wrong moment); <c>Reset</c> = purge +
/// drain. The management stats interval is tuned to 500 ms so settles stay fast.
/// </summary>
public sealed class RabbitMqHarness<TProgram> : IDependencyHarness<TProgram>
    where TProgram : class
{
    public const string Queue = "notify.dm";

    /// <summary>The parallel queue a naive worker host consumes (spec/05-gallery.md §0.4).</summary>
    public const string NaiveQueue = "notify.dm.naive";

    private const int ManagementPort = 15672;

    private RabbitMqContainer? _rabbit;
    private IConnection? _connection;
    private IChannel? _channel;
    private HttpClient? _management;

    public string ConnectionString => _rabbit?.GetConnectionString()
        ?? throw new InvalidOperationException("RabbitMqHarness is not started.");

    public void ConfigureWebHostBuilder(IWebHostBuilder builder) =>
        builder.UseSetting("ConnectionStrings:rabbit", ConnectionString);

    public async Task Start(WebApplicationFactory<TProgram> factory, CancellationToken cancellationToken)
    {
        _rabbit = new RabbitMqBuilder(ContainerImages.RabbitMq)
            .WithPortBinding(ManagementPort, assignRandomHostPort: true)
            .WithEnvironment("RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS", "-rabbit collect_statistics_interval 500")
            .Build();
        await _rabbit.StartAsync(cancellationToken);

        var uri = new Uri(ConnectionString);
        _connection = await new ConnectionFactory { Uri = uri }.CreateConnectionAsync(cancellationToken);
        _channel = await _connection.CreateChannelAsync(cancellationToken: cancellationToken);
        await NotificationQueues.Declare(_channel, Queue, cancellationToken);
        await NotificationQueues.Declare(_channel, NaiveQueue, cancellationToken);

        _management = CreateManagementClient(uri);
        await AwaitManagementReady(cancellationToken);
    }

    public async Task Stop(CancellationToken cancellationToken)
    {
        _management?.Dispose();
        if (_channel is not null)
        {
            await _channel.DisposeAsync();
        }

        if (_connection is not null)
        {
            await _connection.DisposeAsync();
        }

        if (_rabbit is not null)
        {
            await _rabbit.StopAsync(cancellationToken);
            await _rabbit.DisposeAsync();
        }
    }

    /// <summary>Seed: publish a job directly — a duplicate of a delivered one, or poison.</summary>
    public async Task Publish(NotificationJob job, string queue = Queue, CancellationToken ct = default)
    {
        await Channel.BasicPublishAsync(
            exchange: "",
            routingKey: queue,
            mandatory: false,
            basicProperties: new BasicProperties { Persistent = true },
            body: NotificationQueues.Serialize(job),
            cancellationToken: ct);
    }

    /// <summary>Real-time ready count via AMQP passive declare (management stats lag).</summary>
    public async Task<long> ReadyCount(string queue, CancellationToken ct = default) =>
        (await Channel.QueueDeclarePassiveAsync(queue, ct)).MessageCount;

    /// <summary>
    /// Await-until the queue is fully settled: nothing ready AND nothing in flight.
    /// Ready comes from the real-time passive declare; in-flight (unacknowledged) is only
    /// visible through the management API, whose stats refresh on an interval — a single
    /// stale-zero sample right after a publish would falsely report "settled", so the
    /// condition must hold across TWO samples spaced wider than the stats interval
    /// (500 ms, tuned at container start).
    /// </summary>
    public async Task AwaitSettled(string queue, CancellationToken ct)
    {
        var settledSamples = 0;
        while (settledSamples < 2)
        {
            var ready = await ReadyCount(queue, ct);
            var (_, unacked) = await QueueStats(queue, ct);
            if (ready == 0 && unacked == 0)
            {
                settledSamples++;
                if (settledSamples < 2)
                {
                    await Task.Delay(600, ct);
                }
            }
            else
            {
                settledSamples = 0;
                await Task.Delay(100, ct);
            }
        }
    }

    /// <summary>
    /// Await-until the (consumer-less, e.g. DLQ) queue holds exactly
    /// <paramref name="depth"/> messages — real-time via passive declare.
    /// </summary>
    public async Task AwaitDepth(string queue, int depth, CancellationToken ct)
    {
        while (await ReadyCount(queue, ct) != depth)
        {
            await Task.Delay(100, ct);
        }
    }

    /// <summary>(ready, unacknowledged) from the management API.</summary>
    public async Task<(long Ready, long Unacked)> QueueStats(string queue, CancellationToken ct)
    {
        using var response = await Management.GetAsync(new Uri($"/api/queues/%2F/{queue}", UriKind.Relative), ct);
        if (!response.IsSuccessStatusCode)
        {
            return (0, 0);
        }

        using var stats = JsonDocument.Parse(await response.Content.ReadAsStringAsync(ct));
        return (ReadCount(stats, "messages_ready"), ReadCount(stats, "messages_unacknowledged"));
    }

    /// <summary>
    /// Reset: purge everything ready, then wait out anything in flight. Single-sample on
    /// purpose (fast path for the many tests that never touch RabbitMQ): every Rabbit
    /// test ends on its own terminal await, so nothing is in flight when reset runs.
    /// </summary>
    public async Task Drain(CancellationToken ct)
    {
        string[] queues =
        [
            Queue, NotificationQueues.DeadLetterQueue(Queue),
            NaiveQueue, NotificationQueues.DeadLetterQueue(NaiveQueue),
        ];

        while (true)
        {
            var settled = true;
            foreach (var queue in queues)
            {
                var ready = await ReadyCount(queue, ct);
                var (_, unacked) = await QueueStats(queue, ct);
                if (ready > 0)
                {
                    await Channel.QueuePurgeAsync(queue, ct);
                }

                settled &= ready == 0 && unacked == 0;
            }

            if (settled)
            {
                return;
            }

            await Task.Delay(100, ct);
        }
    }

    private static long ReadCount(JsonDocument stats, string field) =>
        stats.RootElement.TryGetProperty(field, out var value) ? value.GetInt64() : 0;

    private HttpClient CreateManagementClient(Uri amqpUri)
    {
        var client = new HttpClient
        {
            BaseAddress = new Uri($"http://{_rabbit!.Hostname}:{_rabbit.GetMappedPublicPort(ManagementPort)}"),
        };
        var userInfo = amqpUri.UserInfo.Split(':');
        client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue(
            "Basic", Convert.ToBase64String(Encoding.UTF8.GetBytes($"{userInfo[0]}:{userInfo[1]}")));
        return client;
    }

    private async Task AwaitManagementReady(CancellationToken ct)
    {
        while (true)
        {
            try
            {
                using var response = await Management.GetAsync(new Uri("/api/overview", UriKind.Relative), ct);
                if (response.IsSuccessStatusCode)
                {
                    return;
                }
            }
            catch (HttpRequestException)
            {
                // Management plugin still booting.
            }

            await Task.Delay(250, ct);
        }
    }

    private IChannel Channel => _channel ?? throw new InvalidOperationException("RabbitMqHarness is not started.");

    private HttpClient Management =>
        _management ?? throw new InvalidOperationException("RabbitMqHarness is not started.");
}
