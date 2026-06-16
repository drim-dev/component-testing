using System.Text.Json;
using Confluent.Kafka;
using Microsoft.Extensions.Configuration;
using Relay.Api.Common.Exceptions;

namespace Relay.Api.Features.Channels;

/// <summary>The <c>message.posted</c> event (spec/04-dependencies.md §3): key = channelId, JSON payload.</summary>
public sealed record MessagePostedEvent(
    string MessageId,
    string ChannelId,
    string SenderId,
    string Preview,
    DateTime PostedAt);

/// <summary>
/// The G-KAFKA producer seam. The correct implementation AWAITS broker confirmation —
/// if the broker is unavailable the publish throws, the surrounding transaction rolls
/// back, and the API answers 503 (`02-api.md` §3 pinned ordering: insert →
/// publish-confirmed → commit). The naive variant fires and forgets: 201, message
/// persisted, event silently lost, feeds never update.
/// </summary>
public interface IMessagePostedPublisher
{
    Task Publish(MessagePostedEvent message, CancellationToken ct);
}

public sealed class KafkaMessagePostedPublisher(IProducer<string, string> producer, IConfiguration configuration)
    : IMessagePostedPublisher
{
    private readonly string _topic = configuration.GetMessagePostedTopic();

    public async Task Publish(MessagePostedEvent message, CancellationToken ct)
    {
        try
        {
            await producer.ProduceAsync(_topic, KafkaEvents.Serialize(message), ct);
        }
        catch (KafkaException ex)
        {
            throw new InfrastructureUnavailableException(
                "events:unavailable", $"The event broker is unavailable: {ex.Error.Reason}");
        }
    }
}

public static class KafkaEvents
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public static Message<string, string> Serialize(MessagePostedEvent message) => new()
    {
        Key = message.ChannelId,
        Value = JsonSerializer.Serialize(message, JsonOptions),
    };

    public static MessagePostedEvent Deserialize(string payload) =>
        JsonSerializer.Deserialize<MessagePostedEvent>(payload, JsonOptions)
        ?? throw new InvalidOperationException("message.posted payload could not be read.");

    public static string GetMessagePostedTopic(this IConfiguration configuration) =>
        configuration["Kafka:Topic"] ?? "message-posted";

    public static string GetFeedFanoutGroupId(this IConfiguration configuration) =>
        configuration["Kafka:GroupId"] ?? "feed-fanout";
}
