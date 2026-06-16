using Microsoft.EntityFrameworkCore;
using Relay.Api.Database;
using Relay.Api.Domain.Attachments;
using Relay.Api.Features.Attachments;

namespace Relay.Api.Tests.Naive;

/// <summary>
/// G-S3 naive variant: the handler looks up the storage key by attachment id and streams
/// the bytes — possession of the id IS access. The channel membership that actually
/// governs the attachment is never consulted (the object store makes "bytes by key" the
/// tempting default shape).
/// </summary>
public sealed class NaiveAttachmentAccess(AppDbContext db) : IAttachmentAccess
{
    public async Task<Attachment> Authorize(string attachmentId, string userId, CancellationToken ct) =>
        await db.Attachments.AsNoTracking().FirstOrDefaultAsync(a => a.Id == attachmentId, ct)
        ?? throw AttachmentAccess.NotFound();
}
