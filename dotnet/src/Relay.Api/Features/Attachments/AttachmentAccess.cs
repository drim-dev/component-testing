using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Exceptions;
using Relay.Api.Database;
using Relay.Api.Domain.Attachments;

namespace Relay.Api.Features.Attachments;

/// <summary>
/// Download authorization (the G-S3 seam). Access derives from the CHANNEL MEMBERSHIP of
/// the attachment's channel — never from possession of the attachment id or the storage
/// key. Unknown id and private-channel non-member return the same existence-hiding 404;
/// public-channel non-member gets the visible-but-forbidden 403.
/// </summary>
public interface IAttachmentAccess
{
    Task<Attachment> Authorize(string attachmentId, string userId, CancellationToken ct);
}

public sealed class AttachmentAccess(AppDbContext db) : IAttachmentAccess
{
    public async Task<Attachment> Authorize(string attachmentId, string userId, CancellationToken ct)
    {
        var attachment = await db.Attachments.AsNoTracking()
            .FirstOrDefaultAsync(a => a.Id == attachmentId, ct)
            ?? throw NotFound();

        var isMember = await db.ChannelMembers.AsNoTracking()
            .AnyAsync(m => m.ChannelId == attachment.ChannelId && m.UserId == userId, ct);
        if (isMember)
        {
            return attachment;
        }

        var channel = await db.Channels.AsNoTracking().FirstAsync(c => c.Id == attachment.ChannelId, ct);
        throw channel.Private
            ? NotFound()
            : new ForbiddenException("channel:membership_required", "Membership is required to download this attachment.");
    }

    internal static NotFoundException NotFound() =>
        new("attachment:not_found", "Attachment not found.");
}
