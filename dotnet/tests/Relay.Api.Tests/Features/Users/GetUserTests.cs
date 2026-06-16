using System.Net;
using FluentAssertions;
using Relay.Api.Tests.Infrastructure;

namespace Relay.Api.Tests.Features.Users;

[Collection(RelayCollection.Name)]
public sealed class GetUserTests(TestFixture fixture) : RelayTest(fixture)
{
    [Fact]
    public async Task S_US_05_get_existing_user_returns_200()
    {
        var ada = await Seed.User("ada");
        var client = Fixture.Http.CreateClient(ada.Id);

        var response = await client.GetAsync($"/users/{ada.Id}");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        var body = await response.ReadJson<UserResponse>();
        body.Handle.Should().Be("ada");
    }

    [Fact]
    public async Task S_US_06_get_unknown_user_returns_404()
    {
        var ada = await Seed.User("ada");
        var client = Fixture.Http.CreateClient(ada.Id);

        var response = await client.GetAsync("/users/NOPE0000NOPE0");

        response.StatusCode.Should().Be(HttpStatusCode.NotFound);
        (await response.ReadError()).Code.Should().Be("user:not_found");
    }

    private sealed record UserResponse(string Id, string Handle, string DisplayName, DateTime CreatedAt);
}
