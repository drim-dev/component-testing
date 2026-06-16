using System.Net;
using System.Net.Http.Json;
using FluentAssertions;
using Relay.Api.Domain.Attachments;
using Relay.Api.Domain.Channels;
using Relay.Api.Features.Attachments;
using Relay.Api.Tests.Infrastructure;
using Relay.Api.Tests.Naive;

namespace Relay.Api.Tests.Features.Attachments;

/// <summary>
/// G-S3: same family as G-IDOR, different dependency shape — download authorization
/// derives from channel membership, never from possession of the attachment id.
/// </summary>
[Collection(RelayCollection.Name)]
public sealed class AttachmentTests(TestFixture fixture) : RelayTest(fixture)
{
    [Fact]
    public async Task S_AT_01_member_upload_lands_bytes_in_the_store_and_a_metadata_row()
    {
        var (owner, channel) = await PublicChannel();
        var content = RandomBytes(10 * 1024);

        var uploaded = await Upload(owner.Id, channel.Id, content, "report.pdf");

        uploaded.ChannelId.Should().Be(channel.Id);
        uploaded.Filename.Should().Be("report.pdf");
        uploaded.SizeBytes.Should().Be(content.Length);

        var stored = await Fixture.Database.SingleOrDefault<Attachment>(a => a.Id == uploaded.Id);
        stored.Should().NotBeNull();
        stored!.UploaderId.Should().Be(owner.Id);
        (await Fixture.S3.GetObjectBytes(stored.StorageKey)).Should().Equal(content);
    }

    [Fact]
    public async Task S_AT_02_non_member_upload_is_rejected_by_visibility()
    {
        var owner = await Seed.User("ada");
        var outsider = await Seed.User("cleo");
        var publicChannel = await Seed.Channel("general", isPrivate: false, owner);
        var privateChannel = await Seed.Channel("secret", isPrivate: true, owner);

        var publicUpload = await TryUpload(outsider.Id, publicChannel.Id, RandomBytes(16));
        publicUpload.StatusCode.Should().Be(HttpStatusCode.Forbidden);

        var privateUpload = await TryUpload(outsider.Id, privateChannel.Id, RandomBytes(16));
        privateUpload.StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task S_AT_03_oversized_and_empty_uploads_are_rejected()
    {
        var (owner, channel) = await PublicChannel();

        var oversized = await TryUpload(owner.Id, channel.Id, RandomBytes(1024 * 1024 + 1));
        oversized.StatusCode.Should().Be(HttpStatusCode.RequestEntityTooLarge);
        (await oversized.ReadError()).Code.Should().Be("attachment:too_large");

        var empty = await TryUpload(owner.Id, channel.Id, []);
        empty.StatusCode.Should().Be(HttpStatusCode.UnprocessableEntity);
        (await empty.ReadError()).Code.Should().Be("attachment:empty");
    }

    [Fact]
    public async Task S_AT_04_message_may_reference_only_own_attachments_of_this_channel()
    {
        var (ada, channel) = await PublicChannel();
        var bob = await Seed.User("bob");
        await Seed.Member(channel, bob, ChannelRole.Member);
        var otherChannel = await Seed.Channel("elsewhere", isPrivate: false, ada);

        var own = await Upload(ada.Id, channel.Id, RandomBytes(16));
        var posted = await Fixture.Http.CreateClient(ada.Id).PostAsJsonAsync(
            $"/channels/{channel.Id}/messages", new { text = "with file", attachmentIds = new[] { own.Id } });
        posted.StatusCode.Should().Be(HttpStatusCode.Created);
        var message = await posted.ReadJson<MessageResponse>();
        (await Fixture.Database.SingleOrDefault<Attachment>(a => a.Id == own.Id))!
            .MessageId.Should().Be(message.Id);

        var someoneElses = await Upload(ada.Id, channel.Id, RandomBytes(16));
        var bobPost = await Fixture.Http.CreateClient(bob.Id).PostAsJsonAsync(
            $"/channels/{channel.Id}/messages", new { text = "stealing", attachmentIds = new[] { someoneElses.Id } });
        bobPost.StatusCode.Should().Be(HttpStatusCode.UnprocessableEntity);
        (await bobPost.ReadError()).Code.Should().Be("message:attachment:invalid");

        var foreignChannel = await Upload(ada.Id, otherChannel.Id, RandomBytes(16));
        var crossPost = await Fixture.Http.CreateClient(ada.Id).PostAsJsonAsync(
            $"/channels/{channel.Id}/messages", new { text = "cross", attachmentIds = new[] { foreignChannel.Id } });
        crossPost.StatusCode.Should().Be(HttpStatusCode.UnprocessableEntity);
        (await crossPost.ReadError()).Code.Should().Be("message:attachment:invalid");
    }

    [Fact]
    public async Task S_AT_05_member_download_returns_identical_bytes_and_filename()
    {
        var (owner, channel) = await PublicChannel();
        var content = RandomBytes(2048);
        var uploaded = await Upload(owner.Id, channel.Id, content, "design.png");

        var response = await Fixture.Http.CreateClient(owner.Id).GetAsync($"/attachments/{uploaded.Id}");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        (await response.Content.ReadAsByteArrayAsync()).Should().Equal(content);
        response.Content.Headers.ContentDisposition!.FileNameStar.Should().Be("design.png");
    }

    // ---- G-S3: possession of the id is not access ----

    [Fact]
    public async Task S_AT_06_non_member_download_from_private_channel_is_404()
    {
        var (attachmentId, outsider) = await PrivateChannelAttachment();
        await AssertPrivateAttachmentHidden(Fixture.Http.CreateClient(outsider.Id), attachmentId);
    }

    [Fact]
    public async Task S_AT_06_naive_download_by_id_possession_is_caught()
    {
        var (attachmentId, outsider) = await PrivateChannelAttachment();
        var naive = Fixture.NaiveClient<IAttachmentAccess, NaiveAttachmentAccess>(outsider.Id);
        await NaiveDemo.ExpectCatchToFail("G-S3", () => AssertPrivateAttachmentHidden(naive, attachmentId));
    }

    private static async Task AssertPrivateAttachmentHidden(HttpClient nonMember, string attachmentId)
    {
        var response = await nonMember.GetAsync($"/attachments/{attachmentId}");
        response.StatusCode.Should().Be(HttpStatusCode.NotFound);
        (await response.ReadError()).Code.Should().Be("attachment:not_found");

        var unknown = await nonMember.GetAsync("/attachments/UNKNOWN0000000");
        (await response.ReadRawBody()).Should().Be(await unknown.ReadRawBody());
    }

    [Fact]
    public async Task S_AT_07_non_member_download_from_public_channel_is_403()
    {
        var owner = await Seed.User("ada");
        var outsider = await Seed.User("cleo");
        var channel = await Seed.Channel("general", isPrivate: false, owner);
        var uploaded = await Upload(owner.Id, channel.Id, RandomBytes(64));

        var response = await Fixture.Http.CreateClient(outsider.Id).GetAsync($"/attachments/{uploaded.Id}");

        response.StatusCode.Should().Be(HttpStatusCode.Forbidden);
        (await response.ReadError()).Code.Should().Be("channel:membership_required");
    }

    // ---- helpers ----

    private async Task<AttachmentResponse> Upload(
        string userId, string channelId, byte[] content, string filename = "file.bin")
    {
        var response = await TryUpload(userId, channelId, content, filename);
        response.StatusCode.Should().Be(HttpStatusCode.Created);
        return await response.ReadJson<AttachmentResponse>();
    }

    private async Task<HttpResponseMessage> TryUpload(
        string userId, string channelId, byte[] content, string filename = "file.bin")
    {
        using var form = new MultipartFormDataContent();
        using var fileContent = new ByteArrayContent(content);
        form.Add(fileContent, "file", filename);
        return await Fixture.Http.CreateClient(userId).PostAsync($"/channels/{channelId}/attachments", form);
    }

    private async Task<(Domain.Users.User Owner, Domain.Channels.Channel Channel)> PublicChannel()
    {
        var owner = await Seed.User("ada");
        var channel = await Seed.Channel("general", isPrivate: false, owner);
        return (owner, channel);
    }

    private async Task<(string AttachmentId, Domain.Users.User Outsider)> PrivateChannelAttachment()
    {
        var owner = await Seed.User("ada");
        var outsider = await Seed.User("cleo");
        var channel = await Seed.Channel("secret", isPrivate: true, owner);
        var uploaded = await Upload(owner.Id, channel.Id, RandomBytes(128), "secret.txt");
        return (uploaded.Id, outsider);
    }

    private static byte[] RandomBytes(int length)
    {
        var bytes = new byte[length];
        Random.Shared.NextBytes(bytes);
        return bytes;
    }

    private sealed record AttachmentResponse(string Id, string ChannelId, string Filename, long SizeBytes, DateTime CreatedAt);

    private sealed record MessageResponse(string Id, string ChannelId, string SenderId, string Text, DateTime CreatedAt);
}
