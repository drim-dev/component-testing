using Microsoft.EntityFrameworkCore;
using Relay.Api.Domain.Attachments;
using Relay.Api.Domain.Channels;
using Relay.Api.Domain.Feed;
using Relay.Api.Domain.Messages;
using Relay.Api.Domain.Notifications;
using Relay.Api.Domain.Users;

namespace Relay.Api.Database;

public sealed class AppDbContext(DbContextOptions<AppDbContext> options) : DbContext(options)
{
    public DbSet<User> Users => Set<User>();
    public DbSet<DmConversation> DmConversations => Set<DmConversation>();
    public DbSet<DmParticipant> DmParticipants => Set<DmParticipant>();
    public DbSet<DmMessage> DmMessages => Set<DmMessage>();
    public DbSet<Channel> Channels => Set<Channel>();
    public DbSet<ChannelMember> ChannelMembers => Set<ChannelMember>();
    public DbSet<ChannelMessage> ChannelMessages => Set<ChannelMessage>();
    public DbSet<Attachment> Attachments => Set<Attachment>();
    public DbSet<Notification> Notifications => Set<Notification>();
    public DbSet<FeedEntry> FeedEntries => Set<FeedEntry>();

    protected override void OnModelCreating(ModelBuilder b)
    {
        b.Entity<User>(e =>
        {
            e.ToTable("users");
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasColumnName("id");
            e.Property(x => x.Handle).HasColumnName("handle");
            e.Property(x => x.DisplayName).HasColumnName("display_name");
            e.Property(x => x.CreatedAt).HasColumnName("created_at");
            e.HasIndex(x => x.Handle).IsUnique();
        });

        b.Entity<DmConversation>(e =>
        {
            e.ToTable("dm_conversations", t =>
                t.HasCheckConstraint("ck_dm_conversations_pair_order", "user_lo < user_hi"));
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasColumnName("id");
            e.Property(x => x.UserLo).HasColumnName("user_lo");
            e.Property(x => x.UserHi).HasColumnName("user_hi");
            e.Property(x => x.CreatedAt).HasColumnName("created_at");
            e.HasIndex(x => new { x.UserLo, x.UserHi }).IsUnique();
            e.HasMany(x => x.Participants).WithOne().HasForeignKey(p => p.ConversationId);
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserLo).OnDelete(DeleteBehavior.Restrict);
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserHi).OnDelete(DeleteBehavior.Restrict);
        });

        b.Entity<DmParticipant>(e =>
        {
            e.ToTable("dm_participants");
            e.HasKey(x => new { x.ConversationId, x.UserId });
            e.Property(x => x.ConversationId).HasColumnName("conversation_id");
            e.Property(x => x.UserId).HasColumnName("user_id");
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Restrict);
        });

        b.Entity<DmMessage>(e =>
        {
            e.ToTable("dm_messages");
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasColumnName("id");
            e.Property(x => x.ConversationId).HasColumnName("conversation_id");
            e.Property(x => x.SenderId).HasColumnName("sender_id");
            e.Property(x => x.Text).HasColumnName("text");
            e.Property(x => x.CreatedAt).HasColumnName("created_at");
            e.HasIndex(x => new { x.ConversationId, x.CreatedAt, x.Id });
            e.HasOne<DmConversation>().WithMany().HasForeignKey(x => x.ConversationId).OnDelete(DeleteBehavior.Restrict);
            e.HasOne<User>().WithMany().HasForeignKey(x => x.SenderId).OnDelete(DeleteBehavior.Restrict);
        });

        b.Entity<Channel>(e =>
        {
            e.ToTable("channels");
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasColumnName("id");
            e.Property(x => x.Name).HasColumnName("name");
            e.Property(x => x.Private).HasColumnName("private");
            e.Property(x => x.CreatedAt).HasColumnName("created_at");
            e.HasMany(x => x.Members).WithOne().HasForeignKey(m => m.ChannelId).OnDelete(DeleteBehavior.Cascade);
        });

        b.Entity<ChannelMember>(e =>
        {
            e.ToTable("channel_members", t =>
                t.HasCheckConstraint("ck_channel_members_role", "role IN ('owner','admin','member')"));
            e.HasKey(x => new { x.ChannelId, x.UserId });
            e.Property(x => x.ChannelId).HasColumnName("channel_id");
            e.Property(x => x.UserId).HasColumnName("user_id");
            e.Property(x => x.Role).HasColumnName("role")
                .HasConversion(r => ChannelRoleNames.ToDbValue(r), v => ChannelRoleNames.FromDbValue(v));
            e.Property(x => x.JoinedAt).HasColumnName("joined_at");
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Restrict);
        });

        b.Entity<ChannelMessage>(e =>
        {
            e.ToTable("channel_messages");
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasColumnName("id");
            e.Property(x => x.ChannelId).HasColumnName("channel_id");
            e.Property(x => x.SenderId).HasColumnName("sender_id");
            e.Property(x => x.Text).HasColumnName("text");
            e.Property(x => x.LinkPreviewTitle).HasColumnName("link_preview_title");
            e.Property(x => x.CreatedAt).HasColumnName("created_at");
            e.HasOne<Channel>().WithMany().HasForeignKey(x => x.ChannelId).OnDelete(DeleteBehavior.Cascade);
            e.HasOne<User>().WithMany().HasForeignKey(x => x.SenderId).OnDelete(DeleteBehavior.Restrict);
            e.HasIndex(x => new { x.ChannelId, x.CreatedAt, x.Id });
        });

        b.Entity<Attachment>(e =>
        {
            e.ToTable("attachments");
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasColumnName("id");
            e.Property(x => x.ChannelId).HasColumnName("channel_id");
            e.Property(x => x.UploaderId).HasColumnName("uploader_id");
            e.Property(x => x.MessageId).HasColumnName("message_id");
            e.Property(x => x.Filename).HasColumnName("filename");
            e.Property(x => x.SizeBytes).HasColumnName("size_bytes");
            e.Property(x => x.StorageKey).HasColumnName("storage_key");
            e.Property(x => x.CreatedAt).HasColumnName("created_at");
            e.HasOne<Channel>().WithMany().HasForeignKey(x => x.ChannelId).OnDelete(DeleteBehavior.Cascade);
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UploaderId).OnDelete(DeleteBehavior.Restrict);
            // SetNull keeps the multi-path channel-delete cascade order-independent: both
            // the attachment and its message die through the channel FK in one statement.
            e.HasOne<ChannelMessage>().WithMany().HasForeignKey(x => x.MessageId).OnDelete(DeleteBehavior.SetNull);
            e.HasIndex(x => x.StorageKey).IsUnique();
        });

        b.Entity<Notification>(e =>
        {
            e.ToTable("notifications");
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasColumnName("id");
            e.Property(x => x.UserId).HasColumnName("user_id");
            e.Property(x => x.DmMessageId).HasColumnName("dm_message_id");
            e.Property(x => x.ConversationId).HasColumnName("conversation_id");
            e.Property(x => x.SenderId).HasColumnName("sender_id");
            e.Property(x => x.Preview).HasColumnName("preview");
            e.Property(x => x.CreatedAt).HasColumnName("created_at");
            e.HasIndex(x => x.DmMessageId).IsUnique();
            e.HasIndex(x => new { x.UserId, x.CreatedAt });
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Restrict);
            e.HasOne<User>().WithMany().HasForeignKey(x => x.SenderId).OnDelete(DeleteBehavior.Restrict);
            e.HasOne<DmMessage>().WithMany().HasForeignKey(x => x.DmMessageId).OnDelete(DeleteBehavior.Restrict);
            e.HasOne<DmConversation>().WithMany().HasForeignKey(x => x.ConversationId).OnDelete(DeleteBehavior.Restrict);
        });

        b.Entity<FeedEntry>(e =>
        {
            e.ToTable("feed_entries");
            e.HasKey(x => x.Id);
            e.Property(x => x.Id).HasColumnName("id");
            e.Property(x => x.UserId).HasColumnName("user_id");
            e.Property(x => x.ChannelId).HasColumnName("channel_id");
            e.Property(x => x.MessageId).HasColumnName("message_id");
            e.Property(x => x.SenderId).HasColumnName("sender_id");
            e.Property(x => x.Preview).HasColumnName("preview");
            e.Property(x => x.CreatedAt).HasColumnName("created_at");
            e.HasOne<Channel>().WithMany().HasForeignKey(x => x.ChannelId).OnDelete(DeleteBehavior.Cascade);
            e.HasOne<User>().WithMany().HasForeignKey(x => x.UserId).OnDelete(DeleteBehavior.Restrict);
            e.HasOne<User>().WithMany().HasForeignKey(x => x.SenderId).OnDelete(DeleteBehavior.Restrict);
            // message_id deliberately carries NO FK (spec/03-schema.md): the pinned
            // publish-confirmed-then-commit ordering means the projection may briefly
            // lead the source table.
            e.HasIndex(x => new { x.UserId, x.MessageId }).IsUnique();
            e.HasIndex(x => new { x.UserId, x.CreatedAt });
        });
    }
}
