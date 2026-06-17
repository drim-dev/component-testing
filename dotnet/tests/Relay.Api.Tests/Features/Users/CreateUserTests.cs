using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using FluentValidation.TestHelper;
using Relay.Api.Domain.Users;
using Relay.Api.Features.Users;
using Relay.Api.Tests.Infrastructure;

namespace Relay.Api.Tests.Features.Users;

[Collection(RelayCollection.Name)]
public sealed class CreateUserTests(TestFixture fixture) : RelayTest(fixture)
{
    [Fact]
    public async Task S_US_01_create_user_returns_201_with_echoed_fields()
    {
        var client = Fixture.Http.CreateClient();

        var response = await client.PostAsJsonAsync("/users", new { handle = "ada", displayName = "Ada Lovelace" });

        response.StatusCode.Should().Be(HttpStatusCode.Created);
        var body = await response.ReadJson<UserResponse>();
        body.Handle.Should().Be("ada");
        body.DisplayName.Should().Be("Ada Lovelace");
        body.Id.Should().NotBeNullOrEmpty();

        var stored = await Fixture.Database.SingleOrDefault<User>(u => u.Handle == "ada");
        stored.Should().NotBeNull();
        stored.Handle.Should().Be(body.Handle);
        stored.DisplayName.Should().Be(body.DisplayName);
        stored.Id.Should().Be(body.Id);
    }

    [Fact]
    public async Task S_US_02_duplicate_handle_returns_409()
    {
        await Seed.User("ada");
        var client = Fixture.Http.CreateClient();

        var response = await client.PostAsJsonAsync("/users", new { handle = "ada", displayName = "Another Ada" });

        response.StatusCode.Should().Be(HttpStatusCode.Conflict);
        (await response.ReadError()).Code.Should().Be("user:handle:taken");
    }

    private sealed record UserResponse(string Id, string Handle, string DisplayName, DateTime CreatedAt);

    public sealed class ValidatorTests
    {
        private readonly CreateUser.RequestValidator _validator = new();

        [Theory]
        [InlineData("ab")]
        [InlineData("UPPER")]
        [InlineData("has space")]
        [InlineData("waaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaay_too_long")]
        public void S_US_03_invalid_handle_fails(string handle)
        {
            _validator.TestValidate(new CreateUser.Request(handle, "Name"))
                .ShouldHaveValidationErrorFor(x => x.Handle)
                .WithErrorCode("user:handle:invalid");
        }

        [Fact]
        public void valid_handle_passes()
        {
            _validator.TestValidate(new CreateUser.Request("ada_99", "Name"))
                .ShouldNotHaveValidationErrorFor(x => x.Handle);
        }

        [Theory]
        [InlineData("")]
        [InlineData("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")]
        public void S_US_04_invalid_display_name_fails(string displayName)
        {
            _validator.TestValidate(new CreateUser.Request("ada", displayName))
                .ShouldHaveValidationErrorFor(x => x.DisplayName)
                .WithErrorCode("user:display_name:invalid");
        }

        [Fact]
        public void valid_display_name_passes()
        {
            _validator.TestValidate(new CreateUser.Request("ada", "Ada Lovelace"))
                .ShouldNotHaveValidationErrorFor(x => x.DisplayName);
        }
    }
}
