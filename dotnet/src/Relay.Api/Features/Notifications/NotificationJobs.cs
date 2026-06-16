using System.Text.Json;
using Microsoft.Extensions.Configuration;
using RabbitMQ.Client;

namespace Relay.Api.Features.Notifications;

/// <summary>One notification job per DM message, carrying everything the worker writes.</summary>
public sealed record NotificationJob(
    string DmMessageId,
    string ConversationId,
    string SenderId,
    string RecipientId,
    string Preview);

/// <summary>
/// Enqueues a notification job (queue <c>notify.dm</c>). Published AFTER the DM message
/// transaction commits, awaiting the broker's publisher confirmation
/// (`02-api.md` §2 pinned ordering — a publish failure after commit is a 500 and the
/// message stays).
/// </summary>
public interface INotificationJobs
{
    Task Enqueue(NotificationJob job, CancellationToken ct);
}

public sealed class RabbitNotificationJobs(IConnection connection, IConfiguration configuration)
    : INotificationJobs, IAsyncDisposable
{
    private readonly SemaphoreSlim _gate = new(1, 1);
    private readonly string _queue = configuration.GetNotificationQueue();
    private IChannel? _channel;

    public async Task Enqueue(NotificationJob job, CancellationToken ct)
    {
        await _gate.WaitAsync(ct);
        try
        {
            _channel ??= await OpenChannel(ct);
            await _channel.BasicPublishAsync(
                exchange: "",
                routingKey: _queue,
                mandatory: false,
                basicProperties: new BasicProperties { Persistent = true },
                body: NotificationQueues.Serialize(job),
                cancellationToken: ct);
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task<IChannel> OpenChannel(CancellationToken ct)
    {
        var channel = await connection.CreateChannelAsync(
            new CreateChannelOptions(
                publisherConfirmationsEnabled: true,
                publisherConfirmationTrackingEnabled: true),
            ct);
        await NotificationQueues.Declare(channel, _queue, ct);
        return channel;
    }

    public async ValueTask DisposeAsync()
    {
        if (_channel is not null)
        {
            await _channel.DisposeAsync();
        }

        _gate.Dispose();
    }
}

/// <summary>
/// Queue topology + payload codec, shared by the publisher, the worker and the harness
/// (identical declare arguments — a mismatched redeclare is a channel error).
/// Quorum queue wired to a dead-letter exchange routing to <c>{queue}.dlq</c>; the
/// worker caps attempts at 3 (counting <c>x-acquired-count</c>) and dead-letters with a
/// final <c>requeue: false</c> nack. <c>x-delivery-limit</c> is set as a broker-side
/// backstop only — it counts dead-letter republishes, not requeued nacks, so it does not
/// fire on the worker's retry path (see <see cref="NotificationWorker"/>).
/// </summary>
public static class NotificationQueues
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public static string GetNotificationQueue(this IConfiguration configuration) =>
        configuration["Rabbit:Queue"] ?? "notify.dm";

    public static string DeadLetterQueue(string queue) => $"{queue}.dlq";

    public static async Task Declare(IChannel channel, string queue, CancellationToken ct)
    {
        var dlq = DeadLetterQueue(queue);
        await channel.QueueDeclareAsync(dlq, durable: true, exclusive: false, autoDelete: false,
            arguments: new Dictionary<string, object?> { ["x-queue-type"] = "quorum" },
            cancellationToken: ct);
        await channel.QueueDeclareAsync(queue, durable: true, exclusive: false, autoDelete: false,
            arguments: new Dictionary<string, object?>
            {
                ["x-queue-type"] = "quorum",
                ["x-delivery-limit"] = 2,
                ["x-dead-letter-exchange"] = "",
                ["x-dead-letter-routing-key"] = dlq,
            },
            cancellationToken: ct);
    }

    public static byte[] Serialize(NotificationJob job) =>
        JsonSerializer.SerializeToUtf8Bytes(job, JsonOptions);

    public static NotificationJob Deserialize(ReadOnlyMemory<byte> body) =>
        JsonSerializer.Deserialize<NotificationJob>(body.Span, JsonOptions)
        ?? throw new InvalidOperationException("Notification job payload could not be read.");
}
