using System.Diagnostics;
using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Relay.Api.Features.Channels;
using Relay.Api.Tests.Infrastructure;
using Relay.Api.Tests.Naive;

namespace Relay.Api.Tests.Features.Channels;

/// <summary>
/// G-HTTP: resilience properties (timeout, graceful degradation, circuit breaker) live in
/// the real client config + socket behavior — invisible to a mock that always returns 200
/// instantly. The catches use a REAL stub server (UnfurlHarness) with fault injection: a
/// delay &gt; the 800 ms timeout (S-LP-02) and a breaker that stops calling a dead upstream
/// (S-LP-04). Each carries a naive red→green demonstration.
/// </summary>
[Collection(RelayCollection.Name)]
public sealed class LinkPreviewTests(TestFixture fixture) : RelayTest(fixture)
{
    private const string TextWithUrl = "look at this https://example.com/page";

    [Fact]
    public async Task S_LP_01_post_with_url_unfurls_to_a_title()
    {
        var (owner, channel) = await ChannelWithOwner();
        Fixture.Unfurl.ProgramOk("Example");

        var posted = await Fixture.Http.CreateClient(owner.Id)
            .PostAsJsonAsync($"/channels/{channel.Id}/messages", new { text = TextWithUrl });

        posted.StatusCode.Should().Be(HttpStatusCode.Created);
        (await posted.ReadJson<MessageResponse>()).LinkPreviewTitle.Should().Be("Example");
        Fixture.Unfurl.RequestCount.Should().Be(1);
    }

    // ---- G-HTTP: a slow upstream must not block the post past the timeout ----

    [Fact]
    public async Task S_LP_02_slow_upstream_degrades_to_null_preview_within_deadline()
    {
        var (owner, channel) = await ChannelWithOwner();
        await AssertSlowUpstreamDegrades(Fixture.Http.CreateClient(owner.Id), channel.Id);
    }

    [Fact]
    public async Task S_LP_02_naive_no_timeout_is_caught()
    {
        var (owner, channel) = await ChannelWithOwner();
        var naive = Fixture.NaiveClient<ILinkPreviewer, NaiveLinkPreviewer>(owner.Id);
        await NaiveDemo.ExpectCatchToFail("G-HTTP", () => AssertSlowUpstreamDegrades(naive, channel.Id));
    }

    private async Task AssertSlowUpstreamDegrades(HttpClient poster, string channelId)
    {
        Fixture.Unfurl.ProgramDelay(TimeSpan.FromSeconds(2));

        var stopwatch = Stopwatch.StartNew();
        var posted = await poster.PostAsJsonAsync($"/channels/{channelId}/messages", new { text = TextWithUrl });
        stopwatch.Stop();

        posted.StatusCode.Should().Be(HttpStatusCode.Created);
        (await posted.ReadJson<MessageResponse>()).LinkPreviewTitle.Should().BeNull();
        stopwatch.Elapsed.Should().BeLessThan(TimeSpan.FromSeconds(1.5));
    }

    [Fact]
    public async Task S_LP_03_upstream_500_degrades_to_null_preview()
    {
        var (owner, channel) = await ChannelWithOwner();
        Fixture.Unfurl.ProgramServerError();

        var posted = await Fixture.Http.CreateClient(owner.Id)
            .PostAsJsonAsync($"/channels/{channel.Id}/messages", new { text = TextWithUrl });

        posted.StatusCode.Should().Be(HttpStatusCode.Created);
        (await posted.ReadJson<MessageResponse>()).LinkPreviewTitle.Should().BeNull();
    }

    // ---- G-HTTP: the breaker stops calling a dead upstream after 5 failures ----

    [Fact]
    public async Task S_LP_04_breaker_opens_after_five_failures_and_stops_calling()
    {
        var (owner, channel) = await ChannelWithOwner();
        await AssertBreakerStopsCallingAfterFive(Fixture.Http.CreateClient(owner.Id), channel.Id);
    }

    [Fact]
    public async Task S_LP_04_naive_no_breaker_is_caught()
    {
        var (owner, channel) = await ChannelWithOwner();
        var naive = Fixture.NaiveClient<ILinkPreviewer, NaiveLinkPreviewer>(owner.Id);
        await NaiveDemo.ExpectCatchToFail("G-HTTP", () => AssertBreakerStopsCallingAfterFive(naive, channel.Id));
    }

    private async Task AssertBreakerStopsCallingAfterFive(HttpClient poster, string channelId)
    {
        Fixture.Unfurl.ProgramServerError();

        for (var i = 0; i < LinkPreviewer.FailureThreshold + 1; i++)
        {
            var posted = await poster.PostAsJsonAsync($"/channels/{channelId}/messages", new { text = TextWithUrl });
            posted.StatusCode.Should().Be(HttpStatusCode.Created);
            (await posted.ReadJson<MessageResponse>()).LinkPreviewTitle.Should().BeNull();
        }

        Fixture.Unfurl.RequestCount.Should().Be(
            LinkPreviewer.FailureThreshold, "the breaker opens after 5 failures, so the 6th post never calls upstream");
    }

    // ---- the synchronous proxy: an upstream failure surfaces, not degrades ----

    [Fact]
    public async Task S_LP_05_preview_proxy_maps_upstream_status_and_validates_url()
    {
        var ada = await Seed.User("ada");
        var client = Fixture.Http.CreateClient(ada.Id);

        Fixture.Unfurl.ProgramOk("Example");
        var ok = await client.GetAsync("/links/preview?url=https://example.com");
        ok.StatusCode.Should().Be(HttpStatusCode.OK);
        (await ok.ReadJson<PreviewResponse>()).Title.Should().Be("Example");

        Fixture.Unfurl.ProgramServerError();
        var failed = await client.GetAsync("/links/preview?url=https://example.com");
        failed.StatusCode.Should().Be(HttpStatusCode.BadGateway);
        (await failed.ReadError()).Code.Should().Be("unfurl:upstream_failed");

        var missing = await client.GetAsync("/links/preview");
        missing.StatusCode.Should().Be(HttpStatusCode.UnprocessableEntity);
        (await missing.ReadError()).Code.Should().Be("unfurl:url:invalid");
    }

    private async Task<(Domain.Users.User Owner, Domain.Channels.Channel Channel)> ChannelWithOwner()
    {
        var owner = await Seed.User("ada");
        var channel = await Seed.Channel("general", isPrivate: false, owner);
        return (owner, channel);
    }

    private sealed record MessageResponse(
        string Id,
        string ChannelId,
        string SenderId,
        string Text,
        string[] AttachmentIds,
        string? LinkPreviewTitle,
        DateTime CreatedAt);

    private sealed record PreviewResponse(string Title);
}
