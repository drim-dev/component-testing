using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using FluentValidation.TestHelper;
using Relay.Api.Domain.Messages;
using Relay.Api.Features.Messages;
using Relay.Api.Tests.Infrastructure;
using Relay.Api.Tests.Naive;

namespace Relay.Api.Tests.Features.Messages;

[Collection(RelayCollection.Name)]
public sealed class CreateConversationTests(TestFixture fixture) : RelayTest(fixture)
{
    private sealed record ConversationResponse(string Id, string[] ParticipantIds, DateTime CreatedAt);

    [Fact]
    public async Task S_DM_01_create_returns_201_with_sorted_pair()
    {
        var (ada, bob) = await TwoUsers();
        var client = Fixture.Http.CreateClient(ada.Id);

        var response = await client.PostAsJsonAsync("/dm/conversations", new { recipientId = bob.Id });

        response.StatusCode.Should().Be(HttpStatusCode.Created);
        var body = await response.ReadJson<ConversationResponse>();
        var expected = new[] { ada.Id, bob.Id }.OrderBy(x => x, StringComparer.Ordinal).ToArray();
        body.ParticipantIds.Should().Equal(expected);
    }

    [Fact]
    public async Task S_DM_02_repeat_create_is_idempotent_returns_200_same_id()
    {
        var (ada, bob) = await TwoUsers();
        var from = Fixture.Http.CreateClient(ada.Id);
        var back = Fixture.Http.CreateClient(bob.Id);

        var first = await from.PostAsJsonAsync("/dm/conversations", new { recipientId = bob.Id });
        first.StatusCode.Should().Be(HttpStatusCode.Created);
        var firstId = (await first.ReadJson<ConversationResponse>()).Id;

        var again = await from.PostAsJsonAsync("/dm/conversations", new { recipientId = bob.Id });
        var reverse = await back.PostAsJsonAsync("/dm/conversations", new { recipientId = ada.Id });

        again.StatusCode.Should().Be(HttpStatusCode.OK);
        reverse.StatusCode.Should().Be(HttpStatusCode.OK);
        (await again.ReadJson<ConversationResponse>()).Id.Should().Be(firstId);
        (await reverse.ReadJson<ConversationResponse>()).Id.Should().Be(firstId);
        (await Fixture.Database.Count<DmConversation>(_ => true)).Should().Be(1);
    }

    [Fact]
    public async Task S_DM_03_create_with_self_returns_422()
    {
        var ada = await Seed.User("ada");
        var client = Fixture.Http.CreateClient(ada.Id);

        var response = await client.PostAsJsonAsync("/dm/conversations", new { recipientId = ada.Id });

        response.StatusCode.Should().Be(HttpStatusCode.UnprocessableEntity);
        (await response.ReadError()).Code.Should().Be("dm:recipient:self");
    }

    [Fact]
    public async Task S_DM_04_create_with_unknown_recipient_returns_404()
    {
        var ada = await Seed.User("ada");
        var client = Fixture.Http.CreateClient(ada.Id);

        var response = await client.PostAsJsonAsync("/dm/conversations", new { recipientId = "GHOST0000GHOS" });

        response.StatusCode.Should().Be(HttpStatusCode.NotFound);
        (await response.ReadError()).Code.Should().Be("user:not_found");
    }

    // ---- G-RACE: concurrent creates must resolve to one conversation ----

    private static async Task AssertRaceResolvesToOneRow(HttpClient client, string recipientId, TestFixture fixture)
    {
        var attempts = Enumerable.Range(0, 8)
            .Select(_ => client.PostAsJsonAsync("/dm/conversations", new { recipientId }));
        var responses = await Task.WhenAll(attempts);

        responses.Should().OnlyContain(r => r.StatusCode == HttpStatusCode.Created || r.StatusCode == HttpStatusCode.OK);
        var ids = await Task.WhenAll(responses.Select(async r => (await r.ReadJson<ConversationResponse>()).Id));
        ids.Distinct().Should().HaveCount(1);
        (await fixture.Database.Count<DmConversation>(_ => true)).Should().Be(1);
    }

    [Fact]
    public async Task S_DM_05_concurrent_creates_yield_one_conversation()
    {
        var (ada, bob) = await TwoUsers();
        var client = Fixture.Http.CreateClient(ada.Id);

        await AssertRaceResolvesToOneRow(client, bob.Id, Fixture);
    }

    [Fact]
    public async Task S_DM_05_naive_check_then_insert_is_caught()
    {
        var (ada, bob) = await TwoUsers();
        var naive = Fixture.NaiveClient<IConversationWriter, NaiveRaceConversationWriter>(ada.Id);

        await NaiveDemo.ExpectCatchToFail("G-RACE",
            () => AssertRaceResolvesToOneRow(naive, bob.Id, Fixture));
    }

    // ---- G-TX: partial commit on DM creation ----

    private async Task AssertCreateRollsBackCompletely(HttpClient client, string recipientId)
    {
        await Fixture.Database.ArmParticipantInsertFault(CancellationToken.None);

        var response = await client.PostAsJsonAsync("/dm/conversations", new { recipientId });

        response.StatusCode.Should().Be(HttpStatusCode.InternalServerError);
        (await Fixture.Database.Count<DmConversation>(_ => true)).Should().Be(0);
        (await Fixture.Database.Count<DmParticipant>(_ => true)).Should().Be(0);
    }

    [Fact]
    public async Task S_DM_06_mid_transaction_failure_leaves_no_rows()
    {
        var (ada, bob) = await TwoUsers();
        var client = Fixture.Http.CreateClient(ada.Id);

        await AssertCreateRollsBackCompletely(client, bob.Id);
    }

    [Fact]
    public async Task S_DM_06_naive_non_transactional_writer_is_caught()
    {
        var (ada, bob) = await TwoUsers();
        var naive = Fixture.NaiveClient<IConversationWriter, NaiveTxConversationWriter>(ada.Id);

        await NaiveDemo.ExpectCatchToFail("G-TX",
            () => AssertCreateRollsBackCompletely(naive, bob.Id));
    }

    private async Task<(Domain.Users.User Ada, Domain.Users.User Bob)> TwoUsers() =>
        (await Seed.User("ada"), await Seed.User("bob"));

    public sealed class ValidatorTests
    {
        private readonly CreateConversation.RequestValidator _validator = new();

        [Fact]
        public void empty_recipient_fails()
        {
            _validator.TestValidate(new CreateConversation.Request(""))
                .ShouldHaveValidationErrorFor(x => x.RecipientId)
                .WithErrorCode("dm:recipient:invalid");
        }

        [Fact]
        public void present_recipient_passes()
        {
            _validator.TestValidate(new CreateConversation.Request("u1"))
                .ShouldNotHaveValidationErrorFor(x => x.RecipientId);
        }
    }
}
