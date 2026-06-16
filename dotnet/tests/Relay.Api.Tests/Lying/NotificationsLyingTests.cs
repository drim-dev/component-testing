using FluentAssertions;
using Relay.Api.Features.Notifications;

namespace Relay.Api.Tests.Lying;

/// <summary>
/// LYING TESTS (exhibits, do not copy) — see <see cref="MessagesLyingTests"/> for the
/// framing. The queue-flavored mirror: counting publish calls on a mocked broker says
/// nothing about delivery — RabbitMQ is at-least-once, and the bug lives in how the
/// WORKER handles the redelivered duplicate, which a publish-count assertion never runs.
/// </summary>
public sealed class NotificationsLyingTests
{
    // LYING TEST (exhibit, do not copy) — gallery case G-RABBIT;
    // caught by NotificationTests.S_NT_02_redelivered_job_converges_to_one_row_and_empty_dlq
    [Fact]
    public async Task RabbitLyingTest_verifies_publish_count_not_delivery_semantics()
    {
        var jobs = new RecordingNotificationJobs();
        var job = new NotificationJob("m1", "c1", "ada", "bob", "hello");

        await jobs.Enqueue(job, CancellationToken.None);

        // "Published exactly once" — green, and beside the point: the broker redelivers,
        // and whether ONE notification row survives a redelivery is decided by the
        // worker's duplicate handling. That code never executes in this test's universe.
        jobs.Published.Should().ContainSingle().Which.Should().Be(job);
    }

    private sealed class RecordingNotificationJobs : INotificationJobs
    {
        public List<NotificationJob> Published { get; } = [];

        public Task Enqueue(NotificationJob job, CancellationToken ct)
        {
            Published.Add(job);
            return Task.CompletedTask;
        }
    }
}
