using FluentAssertions;
using Relay.Api.Features.Channels;

namespace Relay.Api.Tests.Lying;

/// <summary>
/// LYING TESTS (exhibits, do not copy) — see <see cref="MessagesLyingTests"/> for the
/// framing. The broker-flavored mirror: a mocked producer that "delivers" by appending
/// to an in-process list fabricates instant, lossless, exactly-once consistency — the
/// three properties a real broker precisely does NOT give you for free.
/// </summary>
public sealed class FeedLyingTests
{
    // LYING TEST (exhibit, do not copy) — gallery case G-KAFKA;
    // caught by FeedFanoutTests.S_FD_01_broker_down_fails_closed_with_503 and
    // FeedFanoutTests.S_FD_05_event_redelivery_keeps_feed_and_counter_converged
    [Fact]
    public async Task KafkaLyingTest_in_process_bus_fabricates_instant_consistency()
    {
        var feed = new List<MessagePostedEvent>();
        IMessagePostedPublisher publisher = new SynchronousBusPublisher(feed);

        await publisher.Publish(
            new MessagePostedEvent("m1", "ch1", "ada", "hello", DateTime.UtcNow), CancellationToken.None);

        // Green — and meaningless: "publishing" and "consuming" were one synchronous list
        // append. Broker unavailability, confirmation, latency and redelivery — the
        // properties S-FD-01/S-FD-05 pin — cannot occur in this universe.
        feed.Should().ContainSingle(e => e.MessageId == "m1");
    }

    private sealed class SynchronousBusPublisher(List<MessagePostedEvent> feed) : IMessagePostedPublisher
    {
        public Task Publish(MessagePostedEvent message, CancellationToken ct)
        {
            feed.Add(message);
            return Task.CompletedTask;
        }
    }
}
