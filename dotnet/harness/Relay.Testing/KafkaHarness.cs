using Confluent.Kafka;
using Confluent.Kafka.Admin;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Relay.Api.Features.Channels;
using Testcontainers.Kafka;

namespace Relay.Testing;

/// <summary>
/// Kafka harness (the async event log). Real single-node KRaft container.
/// <c>Seed</c> = produce a crafted event directly (tests the consumer in isolation from
/// the producer); <c>Assert</c> = await-until — either on the observable state (test-side
/// polling) or on consumer-group lag (<see cref="AwaitConsumed"/>: committed == end
/// offsets, which is deterministic because the app commits only AFTER the projector's
/// effects are persisted); never <c>sleep</c>. <c>Fault control</c> = stop/start the
/// broker container, which makes "broker down" deterministic for the G-KAFKA catch.
/// </summary>
public sealed class KafkaHarness<TProgram> : IDependencyHarness<TProgram>
    where TProgram : class
{
    public const string Topic = "message-posted";
    public const string GroupId = "feed-fanout";

    /// <summary>
    /// A parallel topic/group pair for naive-variant demos: a naive consumer host points
    /// here so its (deliberately buggy) processing never races the suite's correct
    /// consumer — the demo stays deterministic (spec/05-gallery.md §0.4).
    /// </summary>
    public const string NaiveTopic = "message-posted-naive";
    public const string NaiveGroupId = "feed-fanout-naive";

    private KafkaContainer? _kafka;
    private IAdminClient? _admin;
    private IProducer<string, string>? _producer;

    public string BootstrapAddress => _kafka?.GetBootstrapAddress()
        ?? throw new InvalidOperationException("KafkaHarness is not started.");

    public void ConfigureWebHostBuilder(IWebHostBuilder builder) =>
        builder.UseSetting("Kafka:BootstrapServers", BootstrapAddress);

    public async Task Start(WebApplicationFactory<TProgram> factory, CancellationToken cancellationToken)
    {
        _kafka = new KafkaBuilder(ContainerImages.Kafka).Build();
        await _kafka.StartAsync(cancellationToken);

        _admin = new AdminClientBuilder(new AdminClientConfig { BootstrapServers = BootstrapAddress })
            .SetLogHandler((_, _) => { })
            .SetErrorHandler((_, _) => { })
            .Build();
        _producer = new ProducerBuilder<string, string>(new ProducerConfig
            {
                BootstrapServers = BootstrapAddress,
                Acks = Acks.All,
                MessageTimeoutMs = 10000,
            })
            .SetLogHandler((_, _) => { })
            .SetErrorHandler((_, _) => { })
            .Build();

        await CreateTopics(Topic, NaiveTopic);
    }

    public async Task Stop(CancellationToken cancellationToken)
    {
        _producer?.Dispose();
        _admin?.Dispose();
        if (_kafka is not null)
        {
            await _kafka.StopAsync(cancellationToken);
            await _kafka.DisposeAsync();
        }
    }

    /// <summary>Seed: produce a crafted <c>message.posted</c> event directly.</summary>
    public Task Publish(MessagePostedEvent message, string topic = Topic, CancellationToken ct = default) =>
        Producer.ProduceAsync(topic, KafkaEvents.Serialize(message), ct);

    /// <summary>
    /// Await-until the consumer group has consumed (and therefore persisted — commits
    /// happen after processing) everything published to the topic. This is the
    /// deterministic "settled" assertion for redelivery/negative cases: no bounded
    /// sleep, just committed-offset == end-offset.
    /// </summary>
    public async Task AwaitConsumed(CancellationToken ct, string topic = Topic, string groupId = GroupId)
    {
        var partition = new TopicPartition(topic, new Partition(0));
        while (true)
        {
            var end = await EndOffset(partition);
            if (end >= 0 && (end == 0 || await CommittedOffset(groupId, partition) >= end))
            {
                return;
            }

            await Task.Delay(100, ct);
        }
    }

    /// <summary>
    /// Fault control: a deterministic "broker down" for the G-KAFKA catching test.
    /// PAUSE (not stop): a stopped Kafka container re-runs its entrypoint on start and
    /// trips over the already-formatted KRaft storage; pausing freezes the broker —
    /// produce requests time out exactly as if it were gone — and unpausing recovers
    /// deterministically (spec/04-dependencies.md §3 sanctions pause/stop).
    /// </summary>
    public Task StopBroker(CancellationToken ct) => Kafka.PauseAsync(ct);

    public async Task StartBroker(CancellationToken ct)
    {
        await Kafka.UnpauseAsync(ct);
        await AwaitBrokerReady(ct);
        // Pausing the broker can evict the app's consumer from its group; the next test's
        // reset (AwaitConsumed) would then race the rejoin/rebalance and could time out.
        // Block until the feed-fanout group is back to Stable with its partition assigned,
        // so every test starts against a healthy consumer (zero-flake gate).
        await AwaitConsumerGroupStable(GroupId, ct);
    }

    private async Task AwaitBrokerReady(CancellationToken ct)
    {
        while (true)
        {
            try
            {
                Admin.GetMetadata(Topic, TimeSpan.FromSeconds(2));
                return;
            }
            catch (KafkaException)
            {
                await Task.Delay(250, ct);
            }
        }
    }

    private async Task AwaitConsumerGroupStable(string groupId, CancellationToken ct)
    {
        while (true)
        {
            try
            {
                var description = await Admin.DescribeConsumerGroupsAsync([groupId]);
                var group = description.ConsumerGroupDescriptions.SingleOrDefault();
                if (group is { State: ConsumerGroupState.Stable } && group.Members.Count > 0)
                {
                    return;
                }
            }
            catch (KafkaException)
            {
                // Group coordinator still recovering after the unpause.
            }

            await Task.Delay(250, ct);
        }
    }

    private async Task CreateTopics(params string[] topics)
    {
        try
        {
            await Admin.CreateTopicsAsync(topics.Select(t => new TopicSpecification
            {
                Name = t,
                NumPartitions = 1,
                ReplicationFactor = 1,
            }));
        }
        catch (CreateTopicsException ex) when
            (ex.Results.TrueForAll(r => r.Error.Code is ErrorCode.TopicAlreadyExists or ErrorCode.NoError))
        {
            // Idempotent start.
        }
    }

    private async Task<long> EndOffset(TopicPartition partition)
    {
        try
        {
            var result = await Admin.ListOffsetsAsync(
                [new TopicPartitionOffsetSpec { TopicPartition = partition, OffsetSpec = OffsetSpec.Latest() }],
                new ListOffsetsOptions());
            var info = result.ResultInfos.Single();
            return info.TopicPartitionOffsetError.Offset.Value;
        }
        catch (KafkaException)
        {
            // Broker mid-recovery (e.g. just after an unpause) — keep polling, do not abort.
            return -1;
        }
    }

    private async Task<long> CommittedOffset(string groupId, TopicPartition partition)
    {
        try
        {
            var results = await Admin.ListConsumerGroupOffsetsAsync(
                [new ConsumerGroupTopicPartitions(groupId, [partition])]);
            var committed = results
                .SelectMany(r => r.Partitions)
                .FirstOrDefault(p => p.TopicPartition == partition);
            return committed is null || committed.Offset == Offset.Unset ? -1 : committed.Offset.Value;
        }
        catch (KafkaException)
        {
            // The group does not exist yet — nothing has been consumed.
            return -1;
        }
    }

    private KafkaContainer Kafka => _kafka ?? throw new InvalidOperationException("KafkaHarness is not started.");

    private IAdminClient Admin => _admin ?? throw new InvalidOperationException("KafkaHarness is not started.");

    private IProducer<string, string> Producer =>
        _producer ?? throw new InvalidOperationException("KafkaHarness is not started.");
}
