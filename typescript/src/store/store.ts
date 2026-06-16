// store is Relay's PostgreSQL data layer over Prisma Client. The DDL itself
// (CHECK constraints, FKs, ON DELETE CASCADE, the deliberately-absent
// feed_entries.message_id FK, the G-TX trigger) lives in prisma/schema.sql and
// is applied by the DatabaseHarness at boot — Prisma Client is the typed query
// layer over those tables. Constraints here are product behavior: the unique
// pair (RACE), unique notification (RABBIT), and unique feed entry (KAFKA) are
// the backstops the gallery leans on.

import { Prisma, PrismaClient } from '@prisma/client';

import type {
  Attachment,
  Channel,
  ChannelMember,
  ChannelMessage,
  Conversation,
  DmMessage,
  FeedEntry,
  Notification,
  User,
} from '../domain/domain.js';
import { parseRole, roleToString } from '../domain/domain.js';

// isUniqueViolation reports whether err is a Postgres unique-constraint
// violation — the duplicate-treated-as-success path for the RABBIT and KAFKA
// consumers. Prisma surfaces it as P2002; a raw query surfaces SQLSTATE 23505.
export function isUniqueViolation(err: unknown): boolean {
  if (err instanceof Prisma.PrismaClientKnownRequestError && err.code === 'P2002') {
    return true;
  }
  const meta = (err as { meta?: { code?: string } } | undefined)?.meta;
  if (meta?.code === '23505') {
    return true;
  }
  const code = (err as { code?: string } | undefined)?.code;
  return code === '23505';
}

// Tx is the transaction-scoped client Prisma hands an interactive transaction.
export type Tx = Prisma.TransactionClient;

export class Store {
  constructor(readonly prisma: PrismaClient) {}

  // ---- Users ----

  async insertUser(u: User): Promise<void> {
    await this.prisma.user.create({
      data: { id: u.id, handle: u.handle, displayName: u.displayName, createdAt: u.createdAt },
    });
  }

  async userById(id: string): Promise<User | null> {
    const row = await this.prisma.user.findUnique({ where: { id } });
    return row && { id: row.id, handle: row.handle, displayName: row.displayName, createdAt: row.createdAt };
  }

  // ---- Conversations & DM messages ----

  async conversationById(id: string): Promise<Conversation | null> {
    const row = await this.prisma.dmConversation.findUnique({ where: { id } });
    return row && { id: row.id, userLo: row.userLo, userHi: row.userHi, createdAt: row.createdAt };
  }

  async conversationByPair(lo: string, hi: string): Promise<Conversation | null> {
    const row = await this.prisma.dmConversation.findUnique({
      where: { userLo_userHi: { userLo: lo, userHi: hi } },
    });
    return row && { id: row.id, userLo: row.userLo, userHi: row.userHi, createdAt: row.createdAt };
  }

  async conversationsFor(userId: string, before: string, limit: number): Promise<Conversation[]> {
    const rows = await this.prisma.$queryRaw<RawConversation[]>`
      SELECT c.id, c.user_lo, c.user_hi, c.created_at
      FROM dm_conversations c
      JOIN dm_participants p ON p.conversation_id = c.id AND p.user_id = ${userId}
      WHERE (${before} = '' OR c.id < ${before})
      ORDER BY c.id DESC
      LIMIT ${limit}`;
    return rows.map(toConversation);
  }

  async conversationExists(id: string): Promise<boolean> {
    return (await this.prisma.dmConversation.count({ where: { id } })) > 0;
  }

  async insertDmMessage(m: DmMessage): Promise<void> {
    await this.prisma.dmMessage.create({
      data: {
        id: m.id,
        conversationId: m.conversationId,
        senderId: m.senderId,
        text: m.text,
        createdAt: m.createdAt,
      },
    });
  }

  async dmMessages(conversationId: string, before: string, limit: number): Promise<DmMessage[]> {
    const rows = await this.prisma.dmMessage.findMany({
      where: { conversationId, ...(before ? { id: { lt: before } } : {}) },
      orderBy: { id: 'desc' },
      take: limit,
    });
    return rows.map((r) => ({
      id: r.id,
      conversationId: r.conversationId,
      senderId: r.senderId,
      text: r.text,
      createdAt: r.createdAt,
    }));
  }

  async dmMessageExists(conversationId: string, id: string): Promise<boolean> {
    return (await this.prisma.dmMessage.count({ where: { conversationId, id } })) > 0;
  }

  // ---- Channels & members ----

  async insertChannelWithOwner(c: Channel, owner: ChannelMember): Promise<void> {
    await this.prisma.$transaction(async (tx) => {
      await tx.channel.create({
        data: { id: c.id, name: c.name, private: c.private, createdAt: c.createdAt },
      });
      await tx.channelMember.create({
        data: {
          channelId: owner.channelId,
          userId: owner.userId,
          role: roleToString(owner.role),
          joinedAt: owner.joinedAt,
        },
      });
    });
  }

  async channelById(id: string): Promise<Channel | null> {
    const row = await this.prisma.channel.findUnique({ where: { id } });
    return row && { id: row.id, name: row.name, private: row.private, createdAt: row.createdAt };
  }

  async membership(channelId: string, userId: string): Promise<ChannelMember | null> {
    const row = await this.prisma.channelMember.findUnique({
      where: { channelId_userId: { channelId, userId } },
    });
    return (
      row && {
        channelId: row.channelId,
        userId: row.userId,
        role: parseRole(row.role),
        joinedAt: row.joinedAt,
      }
    );
  }

  async memberCount(channelId: string): Promise<number> {
    return this.prisma.channelMember.count({ where: { channelId } });
  }

  // memberIdsExcept returns member ids of a channel except one (the sender) for
  // fan-out. An empty `except` returns all members (channel presence).
  async memberIdsExcept(channelId: string, except: string): Promise<string[]> {
    const rows = await this.prisma.channelMember.findMany({
      where: { channelId, ...(except ? { userId: { not: except } } : {}) },
      select: { userId: true },
    });
    return rows.map((r) => r.userId);
  }

  async insertMember(m: ChannelMember): Promise<void> {
    await this.prisma.channelMember.create({
      data: {
        channelId: m.channelId,
        userId: m.userId,
        role: roleToString(m.role),
        joinedAt: m.joinedAt,
      },
    });
  }

  async updateMemberRole(channelId: string, userId: string, role: import('../domain/domain.js').Role): Promise<void> {
    await this.prisma.channelMember.update({
      where: { channelId_userId: { channelId, userId } },
      data: { role: roleToString(role) },
    });
  }

  async deleteMember(channelId: string, userId: string): Promise<void> {
    await this.prisma.channelMember.delete({ where: { channelId_userId: { channelId, userId } } });
  }

  async deleteChannel(channelId: string): Promise<void> {
    await this.prisma.channel.delete({ where: { id: channelId } });
  }

  async visibleChannels(userId: string, before: string, limit: number): Promise<Channel[]> {
    const rows = await this.prisma.$queryRaw<RawChannel[]>`
      SELECT DISTINCT c.id, c.name, c.private, c.created_at
      FROM channels c
      LEFT JOIN channel_members m ON m.channel_id = c.id AND m.user_id = ${userId}
      WHERE (c.private = false OR m.user_id IS NOT NULL) AND (${before} = '' OR c.id < ${before})
      ORDER BY c.id DESC
      LIMIT ${limit}`;
    return rows.map(toChannel);
  }

  async channelExists(id: string): Promise<boolean> {
    return (await this.prisma.channel.count({ where: { id } })) > 0;
  }

  // ---- Channel messages ----

  async insertChannelMessage(tx: Tx, m: ChannelMessage): Promise<void> {
    await tx.channelMessage.create({
      data: {
        id: m.id,
        channelId: m.channelId,
        senderId: m.senderId,
        text: m.text,
        linkPreviewTitle: m.linkPreviewTitle,
        createdAt: m.createdAt,
      },
    });
  }

  async channelMessages(channelId: string, before: string, limit: number): Promise<ChannelMessage[]> {
    const rows = await this.prisma.channelMessage.findMany({
      where: { channelId, ...(before ? { id: { lt: before } } : {}) },
      orderBy: { id: 'desc' },
      take: limit,
    });
    return rows.map((r) => ({
      id: r.id,
      channelId: r.channelId,
      senderId: r.senderId,
      text: r.text,
      linkPreviewTitle: r.linkPreviewTitle,
      createdAt: r.createdAt,
    }));
  }

  async channelMessageExists(channelId: string, id: string): Promise<boolean> {
    return (await this.prisma.channelMessage.count({ where: { channelId, id } })) > 0;
  }

  async attachMessageToAttachments(tx: Tx, messageId: string, attachmentIds: string[]): Promise<void> {
    if (attachmentIds.length === 0) {
      return;
    }
    await tx.attachment.updateMany({
      where: { id: { in: attachmentIds } },
      data: { messageId },
    });
  }

  // ---- Attachments ----

  async insertAttachment(a: Attachment): Promise<void> {
    await this.prisma.attachment.create({
      data: {
        id: a.id,
        channelId: a.channelId,
        uploaderId: a.uploaderId,
        messageId: a.messageId,
        filename: a.filename,
        sizeBytes: BigInt(a.sizeBytes),
        storageKey: a.storageKey,
        createdAt: a.createdAt,
      },
    });
  }

  async attachmentById(id: string): Promise<Attachment | null> {
    const row = await this.prisma.attachment.findUnique({ where: { id } });
    return (
      row && {
        id: row.id,
        channelId: row.channelId,
        uploaderId: row.uploaderId,
        messageId: row.messageId,
        filename: row.filename,
        sizeBytes: Number(row.sizeBytes),
        storageKey: row.storageKey,
        createdAt: row.createdAt,
      }
    );
  }

  // attachmentsOwnedInChannel returns ids among attachmentIds that the uploader
  // owns in this channel — for message-create attachment validation.
  async attachmentsOwnedInChannel(channelId: string, uploaderId: string, ids: string[]): Promise<string[]> {
    const rows = await this.prisma.attachment.findMany({
      where: { channelId, uploaderId, id: { in: ids } },
      select: { id: true },
    });
    return rows.map((r) => r.id);
  }

  // ---- Notifications ----

  async insertNotification(n: Notification): Promise<void> {
    await this.prisma.notification.create({
      data: {
        id: n.id,
        userId: n.userId,
        dmMessageId: n.dmMessageId,
        conversationId: n.conversationId,
        senderId: n.senderId,
        preview: n.preview,
        createdAt: n.createdAt,
      },
    });
  }

  async notificationsFor(userId: string, before: string, limit: number): Promise<Notification[]> {
    const rows = await this.prisma.notification.findMany({
      where: { userId, ...(before ? { id: { lt: before } } : {}) },
      orderBy: { id: 'desc' },
      take: limit,
    });
    return rows.map((r) => ({
      id: r.id,
      userId: r.userId,
      dmMessageId: r.dmMessageId,
      conversationId: r.conversationId,
      senderId: r.senderId,
      preview: r.preview,
      createdAt: r.createdAt,
    }));
  }

  // ---- Feed ----

  async insertFeedEntry(f: FeedEntry): Promise<void> {
    await this.prisma.feedEntry.create({
      data: {
        id: f.id,
        userId: f.userId,
        channelId: f.channelId,
        messageId: f.messageId,
        senderId: f.senderId,
        preview: f.preview,
        createdAt: f.createdAt,
      },
    });
  }

  async feedFor(userId: string, before: string, limit: number): Promise<FeedEntry[]> {
    const rows = await this.prisma.feedEntry.findMany({
      where: { userId, ...(before ? { id: { lt: before } } : {}) },
      orderBy: { id: 'desc' },
      take: limit,
    });
    return rows.map((r) => ({
      id: r.id,
      userId: r.userId,
      channelId: r.channelId,
      messageId: r.messageId,
      senderId: r.senderId,
      preview: r.preview,
      createdAt: r.createdAt,
    }));
  }
}

interface RawConversation {
  id: string;
  user_lo: string;
  user_hi: string;
  created_at: Date;
}
const toConversation = (r: RawConversation): Conversation => ({
  id: r.id,
  userLo: r.user_lo,
  userHi: r.user_hi,
  createdAt: r.created_at,
});

interface RawChannel {
  id: string;
  name: string;
  private: boolean;
  created_at: Date;
}
const toChannel = (r: RawChannel): Channel => ({
  id: r.id,
  name: r.name,
  private: r.private,
  createdAt: r.created_at,
});
