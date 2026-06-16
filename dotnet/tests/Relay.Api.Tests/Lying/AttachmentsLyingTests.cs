using FluentAssertions;
using Relay.Api.Common.Auth;
using Relay.Api.Domain.Attachments;
using Relay.Api.Features.Attachments;

namespace Relay.Api.Tests.Lying;

/// <summary>
/// LYING TESTS (exhibits, do not copy) — see <see cref="MessagesLyingTests"/> for the
/// framing. The object-store mirror: mock the storage to return bytes, stub the access
/// check with an attachment the test built itself, and "verify" the download returns the
/// bytes. The authorization dimension — WHO may fetch those bytes — is absent from this
/// test's universe.
/// </summary>
public sealed class AttachmentsLyingTests
{
    // LYING TEST (exhibit, do not copy) — gallery case G-S3;
    // caught by AttachmentTests.S_AT_06_non_member_download_from_private_channel_is_404
    [Fact]
    public async Task S3LyingTest_mocked_storage_returns_bytes_to_anyone()
    {
        var attachment = new Attachment
        {
            Id = "a1",
            ChannelId = "private-channel",
            UploaderId = "ada",
            Filename = "secret.txt",
            SizeBytes = 5,
            StorageKey = "k1",
        };
        IAttachmentAccess access = new AlwaysGrantsAttachmentAccess(attachment);
        IAttachmentStore store = new CannedStore([1, 2, 3, 4, 5]);
        var caller = new CurrentUser();
        caller.Set("cleo-who-is-not-a-member");
        var handler = new DownloadAttachment.RequestHandler(caller, access, store);

        var response = await handler.Handle(new DownloadAttachment.Request("a1"), CancellationToken.None);

        // Green — a non-member just "downloaded" a private channel's file. The mocked
        // store hands bytes to whoever asks; membership never enters the picture.
        response.Content.Should().Equal(1, 2, 3, 4, 5);
    }

    private sealed class AlwaysGrantsAttachmentAccess(Attachment attachment) : IAttachmentAccess
    {
        public Task<Attachment> Authorize(string attachmentId, string userId, CancellationToken ct) =>
            Task.FromResult(attachment);
    }

    private sealed class CannedStore(byte[] content) : IAttachmentStore
    {
        public Task Save(string storageKey, byte[] bytes, CancellationToken ct) => Task.CompletedTask;

        public Task<byte[]> Read(string storageKey, CancellationToken ct) => Task.FromResult(content);
    }
}
