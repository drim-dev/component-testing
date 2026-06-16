using Microsoft.EntityFrameworkCore;
using Relay.Api.Common.Exceptions;
using Relay.Api.Database;
using Relay.Api.Domain.Channels;
using Relay.Api.Features.Channels;

namespace Relay.Api.Tests.Naive;

/// <summary>
/// G-BOLA-READ naive variant: the read path checks only that the channel exists; the
/// <c>private</c> flag and membership are never consulted for the caller. A private
/// channel becomes readable by anyone who knows its id.
/// </summary>
public sealed class NaiveChannelReadGate(AppDbContext db) : IChannelReadGate
{
    public async Task<Channel> AuthorizeMetadata(string channelId, string userId, CancellationToken ct) =>
        await Load(channelId, ct);

    public async Task<Channel> AuthorizeMessageRead(string channelId, string userId, CancellationToken ct) =>
        await Load(channelId, ct);

    private async Task<Channel> Load(string channelId, CancellationToken ct) =>
        await db.Channels.AsNoTracking().FirstOrDefaultAsync(c => c.Id == channelId, ct)
        ?? throw ChannelReadGate.NotFound();
}

/// <summary>
/// G-BOLA-ROLE naive variant: membership is checked (the caller must be a member) but the
/// ROLE is not — <c>hasRole(atLeast: admin)</c> exists, unused on this route. A plain
/// member can perform admin/owner actions.
/// </summary>
public sealed class NaiveChannelRoleGate(AppDbContext db) : IChannelRoleGate
{
    public async Task<ChannelMember> Authorize(string channelId, string userId, ChannelRole required, CancellationToken ct)
    {
        var channel = await db.Channels.AsNoTracking().FirstOrDefaultAsync(c => c.Id == channelId, ct)
                      ?? throw ChannelReadGate.NotFound();
        var membership = await db.ChannelMembers.AsNoTracking()
            .FirstOrDefaultAsync(m => m.ChannelId == channelId && m.UserId == userId, ct);

        if (membership is null)
        {
            throw channel.Private
                ? ChannelReadGate.NotFound()
                : new ForbiddenException("channel:membership_required", "Membership is required for this action.");
        }

        return membership;
    }
}
