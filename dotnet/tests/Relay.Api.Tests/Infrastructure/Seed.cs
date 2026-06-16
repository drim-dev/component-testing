using Relay.Api.Common.Text;
using Relay.Api.Domain.Channels;
using Relay.Api.Domain.Messages;
using Relay.Api.Domain.Notifications;
using Relay.Api.Domain.Users;

namespace Relay.Api.Tests.Infrastructure;

/// <summary>
/// Reachable-state seeding through the same constraints the product uses. Ids are minted
/// from the suite's generator so they are time-ordered (pagination tiebreak) and never
/// collide with a naive host's generator.
/// </summary>
public sealed class Seed(TestFixture fixture)
{
    private DateTime _clock = new(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);

    private DateTime NextTimestamp() => _clock = _clock.AddSeconds(1);

    public async Task<User> User(string handle)
    {
        var user = new User
        {
            Id = fixture.Ids.Create(),
            Handle = handle,
            DisplayName = handle,
            CreatedAt = NextTimestamp(),
        };
        await fixture.Database.Save(user);
        return user;
    }

    public async Task<Channel> Channel(string name, bool isPrivate, User owner)
    {
        var channel = new Channel { Id = fixture.Ids.Create(), Name = name, Private = isPrivate, CreatedAt = NextTimestamp() };
        var membership = new ChannelMember
        {
            ChannelId = channel.Id,
            UserId = owner.Id,
            Role = ChannelRole.Owner,
            JoinedAt = NextTimestamp(),
        };
        await fixture.Database.Save(channel, membership);
        return channel;
    }

    public async Task Member(Channel channel, User user, ChannelRole role)
    {
        await fixture.Database.Save(new ChannelMember
        {
            ChannelId = channel.Id,
            UserId = user.Id,
            Role = role,
            JoinedAt = NextTimestamp(),
        });
    }

    public async Task<ChannelMessage> ChannelMessage(Channel channel, User sender, string text)
    {
        var message = new ChannelMessage
        {
            Id = fixture.Ids.Create(),
            ChannelId = channel.Id,
            SenderId = sender.Id,
            Text = text,
            CreatedAt = NextTimestamp(),
        };
        await fixture.Database.Save(message);
        return message;
    }

    public async Task<DmConversation> Conversation(User a, User b)
    {
        var (lo, hi) = string.CompareOrdinal(a.Id, b.Id) < 0 ? (a.Id, b.Id) : (b.Id, a.Id);
        var conversation = new DmConversation { Id = fixture.Ids.Create(), UserLo = lo, UserHi = hi, CreatedAt = NextTimestamp() };
        conversation.Participants.Add(new DmParticipant { ConversationId = conversation.Id, UserId = lo });
        conversation.Participants.Add(new DmParticipant { ConversationId = conversation.Id, UserId = hi });
        await fixture.Database.Save(conversation);
        return conversation;
    }

    public async Task<DmMessage> DmMessage(DmConversation conversation, User sender, string text)
    {
        var message = new DmMessage
        {
            Id = fixture.Ids.Create(),
            ConversationId = conversation.Id,
            SenderId = sender.Id,
            Text = text,
            CreatedAt = NextTimestamp(),
        };
        await fixture.Database.Save(message);
        return message;
    }

    public async Task<Notification> Notification(User recipient, DmMessage message)
    {
        var notification = new Notification
        {
            Id = fixture.Ids.Create(),
            UserId = recipient.Id,
            DmMessageId = message.Id,
            ConversationId = message.ConversationId,
            SenderId = message.SenderId,
            Preview = Preview.Of(message.Text),
            CreatedAt = NextTimestamp(),
        };
        await fixture.Database.Save(notification);
        return notification;
    }
}
