using System.Net;
using FluentAssertions;
using Relay.Api.Features.Messages;
using Relay.Api.Tests.Infrastructure;
using Relay.Api.Tests.Naive;

namespace Relay.Api.Tests.Features.Messages;

[Collection(RelayCollection.Name)]
public sealed class DmReadTests(TestFixture fixture) : RelayTest(fixture)
{
    [Fact]
    public async Task S_DM_07_list_returns_only_callers_conversations()
    {
        var ada = await Seed.User("ada");
        var bob = await Seed.User("bob");
        var cleo = await Seed.User("cleo");
        var adaBob = await Seed.Conversation(ada, bob);
        await Seed.Conversation(bob, cleo);

        var response = await Fixture.Http.CreateClient(ada.Id).GetAsync("/dm/conversations");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        var page = await response.ReadJson<Page>();
        page.Items.Should().ContainSingle(c => c.Id == adaBob.Id);
    }

    // ---- G-IDOR: only participants may read a conversation / its messages ----

    private static async Task AssertConversationHidden(HttpClient nonParticipant, string conversationId)
    {
        var visible = await nonParticipant.GetAsync($"/dm/conversations/{conversationId}");
        visible.StatusCode.Should().Be(HttpStatusCode.NotFound);
        (await visible.ReadError()).Code.Should().Be("dm:conversation:not_found");

        var unknown = await nonParticipant.GetAsync("/dm/conversations/UNKNOWN0000000");
        (await visible.ReadRawBody()).Should().Be(await unknown.ReadRawBody());
    }

    private static async Task AssertMessagesHidden(HttpClient nonParticipant, string conversationId)
    {
        var response = await nonParticipant.GetAsync($"/dm/conversations/{conversationId}/messages");
        response.StatusCode.Should().Be(HttpStatusCode.NotFound);
        (await response.ReadError()).Code.Should().Be("dm:conversation:not_found");
    }

    [Fact]
    public async Task S_DM_08_non_participant_conversation_read_is_404()
    {
        var (conv, cleo) = await ConversationWithOutsider();
        await AssertConversationHidden(Fixture.Http.CreateClient(cleo.Id), conv.Id);
    }

    [Fact]
    public async Task S_DM_08_naive_read_by_id_is_caught()
    {
        var (conv, cleo) = await ConversationWithOutsider();
        var naive = Fixture.NaiveClient<IDmAccess, NaiveDmAccess>(cleo.Id);
        await NaiveDemo.ExpectCatchToFail("G-IDOR", () => AssertConversationHidden(naive, conv.Id));
    }

    [Fact]
    public async Task S_DM_09_non_participant_messages_read_is_404()
    {
        var (conv, cleo) = await ConversationWithOutsider(seedMessages: true);
        await AssertMessagesHidden(Fixture.Http.CreateClient(cleo.Id), conv.Id);
    }

    [Fact]
    public async Task S_DM_09_naive_message_read_by_id_is_caught()
    {
        var (conv, cleo) = await ConversationWithOutsider(seedMessages: true);
        var naive = Fixture.NaiveClient<IDmAccess, NaiveDmAccess>(cleo.Id);
        await NaiveDemo.ExpectCatchToFail("G-IDOR", () => AssertMessagesHidden(naive, conv.Id));
    }

    private async Task<(Domain.Messages.DmConversation Conversation, Domain.Users.User Outsider)> ConversationWithOutsider(
        bool seedMessages = false)
    {
        var ada = await Seed.User("ada");
        var bob = await Seed.User("bob");
        var cleo = await Seed.User("cleo");
        var conversation = await Seed.Conversation(ada, bob);
        if (seedMessages)
        {
            await Seed.DmMessage(conversation, ada, "secret one");
            await Seed.DmMessage(conversation, bob, "secret two");
        }

        return (conversation, cleo);
    }

    private sealed record Page(ConversationItem[] Items, string? NextBefore);

    private sealed record ConversationItem(string Id, string[] ParticipantIds, DateTime CreatedAt);
}
