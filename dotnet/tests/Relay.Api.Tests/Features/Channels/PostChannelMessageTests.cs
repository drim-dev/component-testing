using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using FluentValidation.TestHelper;
using Relay.Api.Domain.Channels;
using Relay.Api.Features.Channels;
using Relay.Api.Tests.Infrastructure;

namespace Relay.Api.Tests.Features.Channels;

[Collection(RelayCollection.Name)]
public sealed class PostChannelMessageTests(TestFixture fixture) : RelayTest(fixture)
{
    [Fact]
    public async Task S_CH_23_member_posts_non_member_is_rejected_by_visibility()
    {
        var owner = await Seed.User("ada");
        var outsider = await Seed.User("cleo");
        var publicChannel = await Seed.Channel("general", isPrivate: false, owner);
        var privateChannel = await Seed.Channel("secret", isPrivate: true, owner);

        var posted = await Fixture.Http.CreateClient(owner.Id)
            .PostAsJsonAsync($"/channels/{publicChannel.Id}/messages", new { text = "hello" });
        posted.StatusCode.Should().Be(HttpStatusCode.Created);
        var body = await posted.ReadJson<MessageResponse>();
        body.SenderId.Should().Be(owner.Id);
        body.LinkPreviewTitle.Should().BeNull();
        (await Fixture.Database.Count<ChannelMessage>(m => m.Id == body.Id)).Should().Be(1);

        var outsiderClient = Fixture.Http.CreateClient(outsider.Id);
        var publicPost = await outsiderClient
            .PostAsJsonAsync($"/channels/{publicChannel.Id}/messages", new { text = "intruding" });
        publicPost.StatusCode.Should().Be(HttpStatusCode.Forbidden);

        var privatePost = await outsiderClient
            .PostAsJsonAsync($"/channels/{privateChannel.Id}/messages", new { text = "intruding" });
        privatePost.StatusCode.Should().Be(HttpStatusCode.NotFound);

        (await Fixture.Database.Count<ChannelMessage>(m => m.SenderId == outsider.Id)).Should().Be(0);
    }

    private sealed record MessageResponse(
        string Id,
        string ChannelId,
        string SenderId,
        string Text,
        string[] AttachmentIds,
        string? LinkPreviewTitle,
        DateTime CreatedAt);

    public sealed class ValidatorTests
    {
        private readonly PostChannelMessage.RequestValidator _validator = new();

        [Theory]
        [InlineData("")]
        [InlineData(null)]
        public void S_CH_24_empty_text_fails(string? text)
        {
            _validator.TestValidate(new PostChannelMessage.Request("ch", text!, []))
                .ShouldHaveValidationErrorFor(x => x.Text)
                .WithErrorCode("message:text:invalid");
        }

        [Fact]
        public void S_CH_24_text_over_4000_chars_fails()
        {
            _validator.TestValidate(new PostChannelMessage.Request("ch", new string('x', 4001), []))
                .ShouldHaveValidationErrorFor(x => x.Text)
                .WithErrorCode("message:text:invalid");
        }

        [Fact]
        public void S_CH_24_eleven_attachment_ids_fail()
        {
            var ids = Enumerable.Range(0, 11).Select(i => $"a{i}").ToArray();
            _validator.TestValidate(new PostChannelMessage.Request("ch", "hello", ids))
                .ShouldHaveValidationErrorFor(x => x.AttachmentIds)
                .WithErrorCode("message:attachment:invalid");
        }

        [Fact]
        public void boundary_values_pass()
        {
            var tenIds = Enumerable.Range(0, 10).Select(i => $"a{i}").ToArray();
            _validator.TestValidate(new PostChannelMessage.Request("ch", new string('x', 4000), tenIds))
                .ShouldNotHaveAnyValidationErrors();
        }
    }
}
