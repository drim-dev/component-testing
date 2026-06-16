using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using FluentValidation.TestHelper;
using Relay.Api.Domain.Channels;
using Relay.Api.Features.Channels;
using Relay.Api.Tests.Infrastructure;

namespace Relay.Api.Tests.Features.Channels;

[Collection(RelayCollection.Name)]
public sealed class CreateChannelTests(TestFixture fixture) : RelayTest(fixture)
{
    [Fact]
    public async Task S_CH_01_create_makes_caller_sole_owner()
    {
        var ada = await Seed.User("ada");
        var client = Fixture.Http.CreateClient(ada.Id);

        var response = await client.PostAsJsonAsync("/channels", new { name = "general", @private = false });

        response.StatusCode.Should().Be(HttpStatusCode.Created);
        var body = await response.ReadJson<ChannelResponse>();
        var members = await Fixture.Database.Count<ChannelMember>(m => m.ChannelId == body.Id);
        members.Should().Be(1);
        var owner = await Fixture.Database.SingleOrDefault<ChannelMember>(m => m.ChannelId == body.Id);
        owner!.Role.Should().Be(ChannelRole.Owner);
        owner.UserId.Should().Be(ada.Id);
    }

    private sealed record ChannelResponse(string Id, string Name, bool Private, DateTime CreatedAt);

    public sealed class ValidatorTests
    {
        private readonly CreateChannel.RequestValidator _validator = new();

        [Theory]
        [InlineData("")]
        public void S_CH_02_empty_name_fails(string name)
        {
            _validator.TestValidate(new CreateChannel.Request(name, false))
                .ShouldHaveValidationErrorFor(x => x.Name)
                .WithErrorCode("channel:name:invalid");
        }

        [Fact]
        public void S_CH_02_overlong_name_fails()
        {
            _validator.TestValidate(new CreateChannel.Request(new string('x', 101), false))
                .ShouldHaveValidationErrorFor(x => x.Name)
                .WithErrorCode("channel:name:invalid");
        }

        [Fact]
        public void valid_name_passes()
        {
            _validator.TestValidate(new CreateChannel.Request("general", false))
                .ShouldNotHaveValidationErrorFor(x => x.Name);
        }
    }
}
