using Confluent.Kafka;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Relay.Api.Features.Channels;

namespace Relay.Api.Features.Feed;

/// <summary>
/// The <c>feed-fanout</c> consumer group (spec/04-dependencies.md §3). Offsets are
/// committed only AFTER the projector has applied the event, so "group lag == 0" implies
/// every consumed event's effects are persisted — the property the harness's
/// await-idle assertion leans on. Delivery is therefore at-least-once and the projector
/// is idempotent (see <see cref="IFeedProjector"/>).
/// </summary>
public sealed class FeedFanoutConsumer(
    IServiceScopeFactory scopes,
    IConfiguration configuration,
    ILogger<FeedFanoutConsumer> logger) : BackgroundService
{
    protected override Task ExecuteAsync(CancellationToken stoppingToken) =>
        Task.Factory.StartNew(
            () => RunLoop(stoppingToken),
            stoppingToken,
            TaskCreationOptions.LongRunning,
            TaskScheduler.Default).Unwrap();

    private async Task RunLoop(CancellationToken stoppingToken)
    {
        var config = new ConsumerConfig
        {
            BootstrapServers = configuration["Kafka:BootstrapServers"],
            GroupId = configuration.GetFeedFanoutGroupId(),
            AutoOffsetReset = AutoOffsetReset.Earliest,
            EnableAutoCommit = false,
        };

        using var consumer = new ConsumerBuilder<string, string>(config)
            .SetLogHandler((_, log) => logger.LogDebug("Kafka consumer: {Message}", log.Message))
            .SetErrorHandler((_, error) => logger.LogDebug("Kafka consumer error: {Reason}", error.Reason))
            .Build();
        consumer.Subscribe(configuration.GetMessagePostedTopic());

        try
        {
            while (!stoppingToken.IsCancellationRequested)
            {
                await ConsumeOne(consumer, stoppingToken);
            }
        }
        catch (OperationCanceledException)
        {
            // Shutdown.
        }
        finally
        {
            consumer.Close();
        }
    }

    private async Task ConsumeOne(IConsumer<string, string> consumer, CancellationToken stoppingToken)
    {
        ConsumeResult<string, string> result;
        try
        {
            result = consumer.Consume(stoppingToken);
        }
        catch (ConsumeException ex)
        {
            // Broker unavailable or transient fetch error — librdkafka self-heals; keep polling.
            logger.LogDebug("Kafka consume error: {Reason}", ex.Error.Reason);
            return;
        }

        try
        {
            await using (var scope = scopes.CreateAsyncScope())
            {
                var projector = scope.ServiceProvider.GetRequiredService<IFeedProjector>();
                await projector.Apply(KafkaEvents.Deserialize(result.Message.Value), stoppingToken);
            }

            consumer.Commit(result);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            // Processing failed before the commit: rewind to the same offset and retry —
            // at-least-once, never silently skipped. The projector's idempotency makes
            // the retry safe.
            logger.LogDebug(ex, "Feed fanout failed for offset {Offset}; retrying.", result.TopicPartitionOffset);
            consumer.Seek(result.TopicPartitionOffset);
            await Task.Delay(TimeSpan.FromMilliseconds(250), stoppingToken);
        }
    }
}
