using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using RabbitMQ.Client;
using RabbitMQ.Client.Events;

namespace Relay.Api.Features.Notifications;

/// <summary>
/// The <c>notify.dm</c> worker. Manual acks, prefetch 1: a job is acked only after the
/// recorder persisted its effect. A failing job is retried up to <see cref="MaxAttempts"/>
/// times, then dead-lettered — so the queue keeps flowing past a poison job.
/// </summary>
/// <remarks>
/// The attempt cap is enforced in the worker via the <c>x-delivery-count</c> header that
/// quorum queues stamp, NOT by leaning on the broker auto-applying <c>x-delivery-limit</c>:
/// a <c>basic.nack</c> with <c>requeue: true</c> returns the job to the queue without the
/// broker reliably enforcing the limit (it loops). On the final attempt the worker nacks
/// with <c>requeue: false</c>, which routes the job to the DLX → DLQ deterministically.
/// </remarks>
public sealed class NotificationWorker(
    IConnection connection,
    IServiceScopeFactory scopes,
    IConfiguration configuration,
    ILogger<NotificationWorker> logger) : BackgroundService
{
    public const int MaxAttempts = 3;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        var queue = configuration.GetNotificationQueue();
        await using var channel = await connection.CreateChannelAsync(cancellationToken: stoppingToken);
        await NotificationQueues.Declare(channel, queue, stoppingToken);
        await channel.BasicQosAsync(prefetchSize: 0, prefetchCount: 1, global: false, stoppingToken);

        var consumer = new AsyncEventingBasicConsumer(channel);
        consumer.ReceivedAsync += async (_, delivery) =>
        {
            try
            {
                await using (var scope = scopes.CreateAsyncScope())
                {
                    var recorder = scope.ServiceProvider.GetRequiredService<INotificationRecorder>();
                    await recorder.Record(NotificationQueues.Deserialize(delivery.Body), stoppingToken);
                }

                await channel.BasicAckAsync(delivery.DeliveryTag, multiple: false, stoppingToken);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                var exhausted = DeliveryCount(delivery) >= MaxAttempts;
                logger.LogDebug(
                    ex, "Notification job failed (attempt {Attempt}); {Action}.",
                    DeliveryCount(delivery), exhausted ? "dead-lettering" : "requeue");
                await channel.BasicNackAsync(delivery.DeliveryTag, multiple: false, requeue: !exhausted, stoppingToken);
            }
        };
        await channel.BasicConsumeAsync(queue, autoAck: false, consumer, stoppingToken);

        await Task.Delay(System.Threading.Timeout.Infinite, stoppingToken);
    }

    /// <summary>
    /// Which delivery attempt this is (1-based). On a quorum queue a <c>basic.nack</c> with
    /// <c>requeue: true</c> returns the message and stamps <c>x-acquired-count</c> = the
    /// number of times a consumer has already acquired it (absent on the first delivery).
    /// (Note: <c>x-delivery-count</c> is NOT stamped for requeued nacks — only for
    /// dead-letter republishing — so the worker must count acquisitions, not deliveries.)
    /// Attempt = prior acquisitions + 1.
    /// </summary>
    private static long DeliveryCount(BasicDeliverEventArgs delivery)
    {
        var prior = delivery.BasicProperties.Headers?.TryGetValue("x-acquired-count", out var raw) == true
            ? Convert.ToInt64(raw, System.Globalization.CultureInfo.InvariantCulture)
            : 0;
        return prior + 1;
    }
}
