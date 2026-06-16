using FluentAssertions;
using Relay.Api.Features.Channels;

namespace Relay.Api.Tests.Lying;

/// <summary>
/// LYING TESTS (exhibits, do not copy) — see <see cref="MessagesLyingTests"/> for the
/// framing. The outbound-HTTP mirror: mock the previewer (or the HTTP client beneath it)
/// to return a title instantly. Timeouts, sockets, 5xx and the circuit breaker — every
/// resilience property the catching tests exist for — cannot occur in the mock's universe,
/// so the bug (no timeout / no breaker) is unrepresentable here. Green by construction.
/// </summary>
public sealed class LinkPreviewLyingTests
{
    // LYING TEST (exhibit, do not copy) — gallery case G-HTTP;
    // caught by LinkPreviewTests.S_LP_02_slow_upstream_degrades_to_null_preview_within_deadline
    // and LinkPreviewTests.S_LP_04_breaker_opens_after_five_failures_and_stops_calling
    [Fact]
    public async Task HttpLyingTest_mocked_previewer_never_times_out_or_fails()
    {
        ILinkPreviewer previewer = new InstantPreviewer("Example");

        var title = await previewer.TryUnfurl("see https://example.com", CancellationToken.None);

        // Green — and just as green against the naive previewer with no timeout and no
        // breaker: there is no socket to be slow and no upstream to 500, so the assertion
        // only mirrors the canned title.
        title.Should().Be("Example");
    }

    private sealed class InstantPreviewer(string title) : ILinkPreviewer
    {
        public Task<string?> TryUnfurl(string text, CancellationToken ct) => Task.FromResult<string?>(title);
    }
}
