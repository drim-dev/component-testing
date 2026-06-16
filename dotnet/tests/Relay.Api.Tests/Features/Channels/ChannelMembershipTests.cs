using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using FluentValidation.TestHelper;
using Relay.Api.Domain.Channels;
using Relay.Api.Domain.Users;
using Relay.Api.Features.Channels;
using Relay.Api.Tests.Infrastructure;
using Relay.Api.Tests.Naive;

namespace Relay.Api.Tests.Features.Channels;

[Collection(RelayCollection.Name)]
public sealed class ChannelMembershipTests(TestFixture fixture) : RelayTest(fixture)
{
    [Fact]
    public async Task S_CH_06_join_public_makes_member()
    {
        var owner = await Seed.User("ada");
        var joiner = await Seed.User("bob");
        var channel = await Seed.Channel("general", isPrivate: false, owner);

        var response = await Fixture.Http.CreateClient(joiner.Id).PostAsync($"/channels/{channel.Id}/join", null);

        response.StatusCode.Should().Be(HttpStatusCode.Created);
        (await Fixture.Database.SingleOrDefault<ChannelMember>(m => m.ChannelId == channel.Id && m.UserId == joiner.Id))!
            .Role.Should().Be(ChannelRole.Member);
    }

    [Fact]
    public async Task S_CH_07_join_when_already_member_is_409()
    {
        var owner = await Seed.User("ada");
        var channel = await Seed.Channel("general", isPrivate: false, owner);

        var response = await Fixture.Http.CreateClient(owner.Id).PostAsync($"/channels/{channel.Id}/join", null);

        response.StatusCode.Should().Be(HttpStatusCode.Conflict);
        (await response.ReadError()).Code.Should().Be("channel:member:already");
    }

    [Fact]
    public async Task S_CH_08_join_private_as_non_member_is_404()
    {
        var owner = await Seed.User("ada");
        var outsider = await Seed.User("bob");
        var channel = await Seed.Channel("secret", isPrivate: true, owner);

        var response = await Fixture.Http.CreateClient(outsider.Id).PostAsync($"/channels/{channel.Id}/join", null);

        response.StatusCode.Should().Be(HttpStatusCode.NotFound);
        (await response.ReadError()).Code.Should().Be("channel:not_found");
    }

    [Fact]
    public async Task S_CH_09_owner_adds_member()
    {
        var world = await World();
        var response = await Fixture.Http.CreateClient(world.Owner.Id)
            .PostAsJsonAsync($"/channels/{world.Channel.Id}/members", new { userId = world.Outsider.Id });

        response.StatusCode.Should().Be(HttpStatusCode.Created);
    }

    [Fact]
    public async Task S_CH_10_admin_adds_member()
    {
        var world = await World();
        var response = await Fixture.Http.CreateClient(world.Admin.Id)
            .PostAsJsonAsync($"/channels/{world.Channel.Id}/members", new { userId = world.Outsider.Id });

        response.StatusCode.Should().Be(HttpStatusCode.Created);
    }

    [Fact]
    public async Task S_CH_12_add_existing_member_is_409()
    {
        var world = await World();
        var response = await Fixture.Http.CreateClient(world.Owner.Id)
            .PostAsJsonAsync($"/channels/{world.Channel.Id}/members", new { userId = world.Member.Id });

        response.StatusCode.Should().Be(HttpStatusCode.Conflict);
        (await response.ReadError()).Code.Should().Be("channel:member:already");
    }

    [Fact]
    public async Task S_CH_13_owner_promotes_member_admin_cannot()
    {
        var world = await World();
        var promote = await Fixture.Http.CreateClient(world.Owner.Id)
            .PostAsync($"/channels/{world.Channel.Id}/members/{world.Member.Id}/promote", null);
        promote.StatusCode.Should().Be(HttpStatusCode.OK);

        var byAdmin = await Fixture.Http.CreateClient(world.Admin.Id)
            .PostAsync($"/channels/{world.Channel.Id}/members/{world.Member.Id}/promote", null);
        byAdmin.StatusCode.Should().Be(HttpStatusCode.Forbidden);
    }

    [Fact]
    public async Task S_CH_14_admin_kicks_member()
    {
        var world = await World();
        var response = await Fixture.Http.CreateClient(world.Admin.Id)
            .DeleteAsync($"/channels/{world.Channel.Id}/members/{world.Member.Id}");

        response.StatusCode.Should().Be(HttpStatusCode.NoContent);
        (await Fixture.Database.Count<ChannelMember>(m => m.ChannelId == world.Channel.Id && m.UserId == world.Member.Id))
            .Should().Be(0);
    }

    [Fact]
    public async Task S_CH_17_member_leaves_owner_cannot()
    {
        var world = await World();
        var leave = await Fixture.Http.CreateClient(world.Member.Id)
            .DeleteAsync($"/channels/{world.Channel.Id}/members/{world.Member.Id}");
        leave.StatusCode.Should().Be(HttpStatusCode.NoContent);

        var ownerLeave = await Fixture.Http.CreateClient(world.Owner.Id)
            .DeleteAsync($"/channels/{world.Channel.Id}/members/{world.Owner.Id}");
        ownerLeave.StatusCode.Should().Be(HttpStatusCode.Conflict);
        (await ownerLeave.ReadError()).Code.Should().Be("channel:owner:cannot_leave");
    }

    [Fact]
    public async Task S_CH_18_owner_kicks_admin_but_admin_cannot_kick_admin()
    {
        var world = await World();
        var eve = await Seed.User("eve");
        var fred = await Seed.User("fred");
        await Seed.Member(world.Channel, eve, ChannelRole.Admin);
        await Seed.Member(world.Channel, fred, ChannelRole.Admin);

        var byOwner = await Fixture.Http.CreateClient(world.Owner.Id)
            .DeleteAsync($"/channels/{world.Channel.Id}/members/{world.Admin.Id}");
        byOwner.StatusCode.Should().Be(HttpStatusCode.NoContent);

        var adminKicksAdmin = await Fixture.Http.CreateClient(eve.Id)
            .DeleteAsync($"/channels/{world.Channel.Id}/members/{fred.Id}");
        adminKicksAdmin.StatusCode.Should().Be(HttpStatusCode.Forbidden);
    }

    [Fact]
    public async Task S_CH_20_owner_deletes_channel_cascades()
    {
        var world = await World();
        await Seed.ChannelMessage(world.Channel, world.Owner, "hello");

        var response = await Fixture.Http.CreateClient(world.Owner.Id).DeleteAsync($"/channels/{world.Channel.Id}");

        response.StatusCode.Should().Be(HttpStatusCode.NoContent);
        (await Fixture.Database.Count<ChannelMember>(m => m.ChannelId == world.Channel.Id)).Should().Be(0);
        (await Fixture.Database.Count<ChannelMessage>(m => m.ChannelId == world.Channel.Id)).Should().Be(0);
    }

    // ---- G-BOLA-ROLE: privileged actions require the role, not just membership ----

    private async Task AssertMemberCannotAdd(HttpClient memberClient, string channelId, string targetUserId)
    {
        var response = await memberClient.PostAsJsonAsync($"/channels/{channelId}/members", new { userId = targetUserId });
        response.StatusCode.Should().Be(HttpStatusCode.Forbidden);
        (await response.ReadError()).Code.Should().Be("channel:role:forbidden");
        (await Fixture.Database.Count<ChannelMember>(m => m.ChannelId == channelId && m.UserId == targetUserId))
            .Should().Be(0);
    }

    [Fact]
    public async Task S_CH_11_member_add_is_403()
    {
        var world = await World();
        await AssertMemberCannotAdd(Fixture.Http.CreateClient(world.Member.Id), world.Channel.Id, world.Outsider.Id);
    }

    [Fact]
    public async Task S_CH_11_naive_skipping_role_is_caught()
    {
        var world = await World();
        var naive = Fixture.NaiveClient<IChannelRoleGate, NaiveChannelRoleGate>(world.Member.Id);
        await NaiveDemo.ExpectCatchToFail("G-BOLA-ROLE",
            () => AssertMemberCannotAdd(naive, world.Channel.Id, world.Outsider.Id));
    }

    private async Task AssertMemberCannotKick(HttpClient memberClient, string channelId, string targetUserId)
    {
        var response = await memberClient.DeleteAsync($"/channels/{channelId}/members/{targetUserId}");
        response.StatusCode.Should().Be(HttpStatusCode.Forbidden);
        (await Fixture.Database.Count<ChannelMember>(m => m.ChannelId == channelId && m.UserId == targetUserId))
            .Should().Be(1);
    }

    [Fact]
    public async Task S_CH_15_member_kick_is_403()
    {
        var world = await World();
        var victim = await Seed.User("zoe");
        await Seed.Member(world.Channel, victim, ChannelRole.Member);
        await AssertMemberCannotKick(Fixture.Http.CreateClient(world.Member.Id), world.Channel.Id, victim.Id);
    }

    [Fact]
    public async Task S_CH_15_naive_skipping_role_is_caught()
    {
        var world = await World();
        var victim = await Seed.User("zoe");
        await Seed.Member(world.Channel, victim, ChannelRole.Member);
        var naive = Fixture.NaiveClient<IChannelRoleGate, NaiveChannelRoleGate>(world.Member.Id);
        await NaiveDemo.ExpectCatchToFail("G-BOLA-ROLE",
            () => AssertMemberCannotKick(naive, world.Channel.Id, victim.Id));
    }

    private async Task AssertCannotDelete(HttpClient caller, string channelId)
    {
        var response = await caller.DeleteAsync($"/channels/{channelId}");
        response.StatusCode.Should().Be(HttpStatusCode.Forbidden);
        (await Fixture.Database.Count<Channel>(c => c.Id == channelId)).Should().Be(1);
    }

    [Fact]
    public async Task S_CH_19_admin_and_member_delete_is_403()
    {
        var world = await World();
        await AssertCannotDelete(Fixture.Http.CreateClient(world.Admin.Id), world.Channel.Id);
        await AssertCannotDelete(Fixture.Http.CreateClient(world.Member.Id), world.Channel.Id);
    }

    [Fact]
    public async Task S_CH_19_naive_skipping_role_is_caught()
    {
        var world = await World();
        var naive = Fixture.NaiveClient<IChannelRoleGate, NaiveChannelRoleGate>(world.Admin.Id);
        await NaiveDemo.ExpectCatchToFail("G-BOLA-ROLE",
            () => AssertCannotDelete(naive, world.Channel.Id));
    }

    private sealed record ChannelWorld(Channel Channel, User Owner, User Admin, User Member, User Outsider);

    private async Task<ChannelWorld> World()
    {
        var owner = await Seed.User("ada");
        var admin = await Seed.User("bob");
        var member = await Seed.User("cleo");
        var outsider = await Seed.User("dan");
        var channel = await Seed.Channel("general", isPrivate: true, owner);
        await Seed.Member(channel, admin, ChannelRole.Admin);
        await Seed.Member(channel, member, ChannelRole.Member);
        return new ChannelWorld(channel, owner, admin, member, outsider);
    }

    public sealed class ValidatorTests
    {
        private readonly AddMember.RequestValidator _validator = new();

        [Theory]
        [InlineData("")]
        [InlineData(null)]
        public void empty_user_id_fails(string? userId)
        {
            _validator.TestValidate(new AddMember.Request("ch", userId!))
                .ShouldHaveValidationErrorFor(x => x.UserId)
                .WithErrorCode("channel:member:invalid");
        }

        [Fact]
        public void a_user_id_passes()
        {
            _validator.TestValidate(new AddMember.Request("ch", "user-1"))
                .ShouldNotHaveAnyValidationErrors();
        }
    }
}
