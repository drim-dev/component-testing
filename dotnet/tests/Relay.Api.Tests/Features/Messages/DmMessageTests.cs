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
public sealed class DmMessageTests(TestFixture fixture) : RelayTest(fixture)
{
    // ---- G-IDOR on the WRITE path: a non-participant must not post into a conversation ----

    [Fact]
    public async Task S_DM_10_non_participant_post_is_404_and_writes_nothing()
    {
        var (conversation, intruder) = await ConversationWithIntruder();
        await AssertIntruderCannotPost(Fixture.Http.CreateClient(intruder.Id), conversation.Id);
    }

    [Fact]
    public async Task S_DM_10_naive_post_without_participant_check_is_caught()
    {
        var (conversation, intruder) = await ConversationWithIntruder();
        var naive = Fixture.NaiveClient<IDmAccess, NaiveDmAccess>(intruder.Id);
        await NaiveDemo.ExpectCatchToFail("G-IDOR", () => AssertIntruderCannotPost(naive, conversation.Id));
    }

    private async Task AssertIntruderCannotPost(HttpClient intruder, string conversationId)
    {
        var response = await intruder.PostAsJsonAsync(
            $"/dm/conversations/{conversationId}/messages", new { text = "let me in" });

        response.StatusCode.Should().Be(HttpStatusCode.NotFound);
        (await response.ReadError()).Code.Should().Be("dm:conversation:not_found");
        (await Fixture.Database.Count<DmMessage>(m => m.Text == "let me in")).Should().Be(0);
    }

    // ---- the G-TAUT honest counterpart: the same behavior through the real system ----

    [Fact]
    public async Task S_DM_11_sent_messages_are_listed_newest_first_for_both_participants()
    {
        var ada = await Seed.User("ada");
        var bob = await Seed.User("bob");
        var conversation = await Seed.Conversation(ada, bob);
        var adaClient = Fixture.Http.CreateClient(ada.Id);

        foreach (var text in new[] { "first", "second", "third" })
        {
            var sent = await adaClient.PostAsJsonAsync(
                $"/dm/conversations/{conversation.Id}/messages", new { text });
            sent.StatusCode.Should().Be(HttpStatusCode.Created);
        }

        foreach (var reader in new[] { ada, bob })
        {
            var page = await (await Fixture.Http.CreateClient(reader.Id)
                .GetAsync($"/dm/conversations/{conversation.Id}/messages")).ReadJson<MessagePage>();
            page.Items.Select(m => m.Text).Should().Equal("third", "second", "first");
            page.Items.Should().OnlyContain(m => m.SenderId == ada.Id);
        }
    }

    private async Task<(Domain.Messages.DmConversation Conversation, Domain.Users.User Intruder)>
        ConversationWithIntruder()
    {
        var ada = await Seed.User("ada");
        var bob = await Seed.User("bob");
        var cleo = await Seed.User("cleo");
        var conversation = await Seed.Conversation(ada, bob);
        await Seed.DmMessage(conversation, ada, "between ada and bob");
        return (conversation, cleo);
    }

    private sealed record MessagePage(MessageItem[] Items, string? NextBefore);

    private sealed record MessageItem(string Id, string ConversationId, string SenderId, string Text, DateTime CreatedAt);

    public sealed class ValidatorTests
    {
        private readonly CreateDmMessage.RequestValidator _validator = new();

        [Theory]
        [InlineData("")]
        [InlineData(null)]
        public void S_DM_12_empty_text_fails(string? text)
        {
            _validator.TestValidate(new CreateDmMessage.Request("c1", text!))
                .ShouldHaveValidationErrorFor(x => x.Text)
                .WithErrorCode("message:text:invalid");
        }

        [Fact]
        public void S_DM_12_text_over_4000_chars_fails()
        {
            _validator.TestValidate(new CreateDmMessage.Request("c1", new string('x', 4001)))
                .ShouldHaveValidationErrorFor(x => x.Text)
                .WithErrorCode("message:text:invalid");
        }

        [Fact]
        public void boundary_text_passes()
        {
            _validator.TestValidate(new CreateDmMessage.Request("c1", new string('x', 4000)))
                .ShouldNotHaveAnyValidationErrors();
        }
    }
}
