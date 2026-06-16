using System.Net;
using FluentAssertions;
using Relay.Api.Tests.Infrastructure;

namespace Relay.Api.Tests.Features.Identity;

[Collection(RelayCollection.Name)]
public sealed class IdentityTests(TestFixture fixture) : RelayTest(fixture)
{
    [Fact]
    public async Task S_ID_01_missing_header_returns_401()
    {
        var client = Fixture.Http.CreateClient();

        var response = await client.GetAsync("/channels");

        response.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
        (await response.ReadError()).Code.Should().Be("identity:missing");
    }

    [Fact]
    public async Task S_ID_02_unknown_user_header_returns_401()
    {
        var client = Fixture.Http.CreateClient("GHOST0000GHOS");

        var response = await client.GetAsync("/channels");

        response.StatusCode.Should().Be(HttpStatusCode.Unauthorized);
        (await response.ReadError()).Code.Should().Be("identity:unknown");
    }
}
