import { Body, Controller, Delete, Get, HttpCode, Inject, Param, Post, Query, Req, Res } from '@nestjs/common';
import type { Response } from 'express';

import { conflict, forbidden, invalid, notFound, notFoundChannel } from '../apierr/apierr.js';
import type { Channel, ChannelMember, ChannelMessage } from '../domain/domain.js';
import { firstUrl, preview, Role } from '../domain/domain.js';
import { IdFactory } from '../idgen/idgen.js';
import { build, errUnknownBefore, parseLimit } from '../paging/paging.js';
import {
  CHANNEL_READ_GATE,
  CHANNEL_ROLE_GATE,
  LINK_PREVIEWER,
  MEMBERSHIP_CACHE,
  MEMBERSHIP_WRITER,
  MESSAGE_POSTED_PUBLISHER,
  UNREAD_COUNTERS,
  type ChannelReadGate,
  type ChannelRoleGate,
  type LinkPreviewer,
  type MembershipCache,
  type MembershipWriter,
  type MessagePostedPublisher,
  type UnreadCounters,
} from '../seams/seams.js';
import { Store } from '../store/store.js';
import { currentUser, type RequestWithUser } from './identity.middleware.js';

interface ChannelDto {
  id: string;
  name: string;
  private: boolean;
  memberCount?: number;
  createdAt: string;
}

interface MembershipDto {
  channelId: string;
  userId: string;
  role: string;
  joinedAt: string;
}

interface ChannelMessageDto {
  id: string;
  channelId: string;
  senderId: string;
  text: string;
  attachmentIds: string[];
  linkPreviewTitle: string | null;
  createdAt: string;
}

function toChannelMessageDto(m: ChannelMessage, attachmentIds: string[]): ChannelMessageDto {
  return {
    id: m.id,
    channelId: m.channelId,
    senderId: m.senderId,
    text: m.text,
    attachmentIds,
    linkPreviewTitle: m.linkPreviewTitle,
    createdAt: m.createdAt.toISOString(),
  };
}

@Controller()
export class ChannelsController {
  constructor(
    private readonly store: Store,
    private readonly ids: IdFactory,
    @Inject(CHANNEL_READ_GATE) private readonly readGate: ChannelReadGate,
    @Inject(CHANNEL_ROLE_GATE) private readonly roleGate: ChannelRoleGate,
    @Inject(MEMBERSHIP_WRITER) private readonly membershipWriter: MembershipWriter,
    @Inject(MESSAGE_POSTED_PUBLISHER) private readonly publisher: MessagePostedPublisher,
    @Inject(LINK_PREVIEWER) private readonly linkPreviewer: LinkPreviewer,
    @Inject(MEMBERSHIP_CACHE) private readonly cache: MembershipCache,
    @Inject(UNREAD_COUNTERS) private readonly unread: UnreadCounters,
  ) {}

  @Post('channels')
  @HttpCode(201)
  async createChannel(@Req() req: RequestWithUser, @Body() body: { name?: string; private?: boolean }): Promise<ChannelDto> {
    const caller = currentUser(req);
    const name = body.name ?? '';
    if ([...name].length < 1 || [...name].length > 100) {
      throw invalid('channel:name:invalid', 'name must be 1–100 chars.');
    }
    const ch: Channel = { id: this.ids.create(), name, private: body.private ?? false, createdAt: new Date() };
    const owner: ChannelMember = { channelId: ch.id, userId: caller.id, role: Role.Owner, joinedAt: new Date() };
    await this.store.insertChannelWithOwner(ch, owner);
    return { id: ch.id, name: ch.name, private: ch.private, createdAt: ch.createdAt.toISOString() };
  }

  @Get('channels')
  async listChannels(@Req() req: RequestWithUser, @Query('limit') limit?: string, @Query('before') before?: string): Promise<unknown> {
    const caller = currentUser(req);
    const parsed = parseLimit(limit);
    const cursor = before ?? '';
    if (cursor !== '' && !(await this.store.channelExists(cursor))) {
      errUnknownBefore();
    }
    const rows = await this.store.visibleChannels(caller.id, cursor, parsed + 1);
    const dtos: ChannelDto[] = [];
    for (const c of rows) {
      const count = await this.store.memberCount(c.id);
      dtos.push({ id: c.id, name: c.name, private: c.private, memberCount: count, createdAt: c.createdAt.toISOString() });
    }
    return build(dtos, parsed, (d) => d.id);
  }

  @Get('channels/:id')
  async getChannel(@Req() req: RequestWithUser, @Param('id') id: string): Promise<ChannelDto> {
    const caller = currentUser(req);
    const ch = await this.readGate.authorizeRead(id, caller.id, false);
    const count = await this.store.memberCount(ch.id);
    return { id: ch.id, name: ch.name, private: ch.private, memberCount: count, createdAt: ch.createdAt.toISOString() };
  }

  @Post('channels/:id/join')
  @HttpCode(201)
  async joinChannel(@Req() req: RequestWithUser, @Param('id') channelId: string): Promise<MembershipDto> {
    const caller = currentUser(req);
    const ch = await this.store.channelById(channelId);
    if (!ch) {
      throw notFoundChannel();
    }
    const member = await this.store.membership(channelId, caller.id);
    if (member) {
      throw conflict('channel:member:already', 'You are already a member.');
    }
    if (ch.private) {
      throw notFoundChannel();
    }
    const m: ChannelMember = { channelId, userId: caller.id, role: Role.Member, joinedAt: new Date() };
    await this.membershipWriter.add(m);
    return { channelId, userId: caller.id, role: 'member', joinedAt: m.joinedAt.toISOString() };
  }

  @Post('channels/:id/members')
  @HttpCode(201)
  async addMember(@Req() req: RequestWithUser, @Param('id') channelId: string, @Body() body: { userId?: string }): Promise<MembershipDto> {
    const caller = currentUser(req);
    await this.roleGate.authorizeRole(channelId, caller.id, Role.Admin);
    const target = await this.store.userById(body.userId ?? '');
    if (!target) {
      throw notFound('user:not_found', 'User not found.');
    }
    const existing = await this.store.membership(channelId, target.id);
    if (existing) {
      throw conflict('channel:member:already', 'That user is already a member.');
    }
    const m: ChannelMember = { channelId, userId: target.id, role: Role.Member, joinedAt: new Date() };
    await this.membershipWriter.add(m);
    return { channelId, userId: target.id, role: 'member', joinedAt: m.joinedAt.toISOString() };
  }

  @Post('channels/:id/members/:userId/promote')
  @HttpCode(200)
  async promoteMember(@Req() req: RequestWithUser, @Param('id') channelId: string, @Param('userId') targetId: string): Promise<MembershipDto> {
    const caller = currentUser(req);
    await this.roleGate.authorizeRole(channelId, caller.id, Role.Owner);
    const target = await this.store.membership(channelId, targetId);
    if (!target) {
      throw notFound('channel:member:not_found', 'Member not found.');
    }
    if (target.role >= Role.Admin) {
      throw conflict('channel:member:already', 'That member is already an admin or owner.');
    }
    await this.store.updateMemberRole(channelId, targetId, Role.Admin);
    return { channelId, userId: targetId, role: 'admin', joinedAt: target.joinedAt.toISOString() };
  }

  @Delete('channels/:id/members/:userId')
  async removeMember(
    @Req() req: RequestWithUser,
    @Res() res: Response,
    @Param('id') channelId: string,
    @Param('userId') targetId: string,
  ): Promise<void> {
    const caller = currentUser(req);
    if (targetId === caller.id) {
      await this.leaveChannel(res, channelId, caller.id);
      return;
    }
    await this.kickMember(res, channelId, caller.id, targetId);
  }

  private async leaveChannel(res: Response, channelId: string, callerId: string): Promise<void> {
    const ch = await this.store.channelById(channelId);
    if (!ch) {
      throw notFoundChannel();
    }
    const member = await this.store.membership(channelId, callerId);
    if (!member) {
      if (ch.private) {
        throw notFoundChannel();
      }
      throw notFound('channel:member:not_found', 'Member not found.');
    }
    if (member.role === Role.Owner) {
      throw conflict('channel:owner:cannot_leave', 'The owner cannot leave their own channel.');
    }
    await this.membershipWriter.remove(channelId, callerId);
    res.status(204).send();
  }

  private async kickMember(res: Response, channelId: string, callerId: string, targetId: string): Promise<void> {
    const callerMembership = await this.roleGate.authorizeRole(channelId, callerId, Role.Admin);
    const target = await this.store.membership(channelId, targetId);
    if (!target) {
      throw notFound('channel:member:not_found', 'Member not found.');
    }
    const kickingPrivileged =
      target.role === Role.Owner || (target.role === Role.Admin && callerMembership.role !== Role.Owner);
    if (kickingPrivileged) {
      throw forbidden('channel:role:forbidden', 'Your role does not permit removing this member.');
    }
    await this.membershipWriter.remove(channelId, targetId);
    res.status(204).send();
  }

  @Delete('channels/:id')
  async deleteChannel(@Req() req: RequestWithUser, @Res() res: Response, @Param('id') channelId: string): Promise<void> {
    const caller = currentUser(req);
    await this.roleGate.authorizeRole(channelId, caller.id, Role.Owner);
    await this.store.deleteChannel(channelId);
    await this.cache.invalidate(channelId);
    res.status(204).send();
  }

  @Post('channels/:id/messages')
  async postChannelMessage(
    @Req() req: RequestWithUser,
    @Res() res: Response,
    @Param('id') channelId: string,
    @Body() body: { text?: string; attachmentIds?: string[] },
  ): Promise<void> {
    const caller = currentUser(req);
    await this.roleGate.authorizeRole(channelId, caller.id, Role.Member);
    const text = body.text ?? '';
    const attachmentIds = body.attachmentIds ?? [];
    if (text.length < 1 || [...text].length > 4000) {
      throw invalid('message:text:invalid', 'text must be 1–4000 chars.');
    }
    if (attachmentIds.length > 10) {
      throw invalid('message:attachment:invalid', 'A message can reference at most 10 attachments.');
    }
    await this.validateAttachments(channelId, caller.id, attachmentIds);

    // Unfurl runs (bounded, graceful) BEFORE the insert so the title persists with
    // the row; a slow/failing upstream degrades to null, never a hang (G-HTTP).
    let title: string | null = null;
    const url = firstUrl(text);
    if (url !== '') {
      title = await this.linkPreviewer.preview(url);
    }

    const msg: ChannelMessage = {
      id: this.ids.create(),
      channelId,
      senderId: caller.id,
      text,
      linkPreviewTitle: title,
      createdAt: new Date(),
    };

    // Pinned write ordering (02-api.md §3, no outbox): open tx → insert message →
    // publish AWAITING broker confirmation → commit. A publish failure rolls back
    // and surfaces 503; the message is never half-posted (G-KAFKA producer).
    await this.store.prisma.$transaction(async (tx) => {
      await this.store.insertChannelMessage(tx, msg);
      await this.store.attachMessageToAttachments(tx, msg.id, attachmentIds);
      await this.publisher.publish({
        messageId: msg.id,
        channelId,
        senderId: caller.id,
        preview: preview(text),
        postedAt: msg.createdAt.toISOString(),
      });
    });

    res.status(201).json(toChannelMessageDto(msg, attachmentIds));
  }

  // validateAttachments enforces S-AT-04: every referenced attachment must be
  // uploaded by the caller to THIS channel. A bad reference is 422 before any write.
  private async validateAttachments(channelId: string, callerId: string, ids: string[]): Promise<void> {
    if (ids.length === 0) {
      return;
    }
    const owned = await this.store.attachmentsOwnedInChannel(channelId, callerId, ids);
    if (owned.length !== ids.length) {
      throw invalid(
        'message:attachment:invalid',
        'Attachments must be uploaded to this channel by you and not already referenced.',
      );
    }
  }

  @Get('channels/:id/messages')
  async getChannelMessages(
    @Req() req: RequestWithUser,
    @Param('id') channelId: string,
    @Query('limit') limit?: string,
    @Query('before') before?: string,
  ): Promise<unknown> {
    const caller = currentUser(req);
    await this.readGate.authorizeRead(channelId, caller.id, true);
    const parsed = parseLimit(limit);
    const cursor = before ?? '';
    if (cursor !== '' && !(await this.store.channelMessageExists(channelId, cursor))) {
      errUnknownBefore();
    }
    const rows = await this.store.channelMessages(channelId, cursor, parsed + 1);
    const dtos = rows.map((m) => toChannelMessageDto(m, []));
    return build(dtos, parsed, (d) => d.id);
  }

  @Post('channels/:id/read')
  async markChannelRead(@Req() req: RequestWithUser, @Res() res: Response, @Param('id') channelId: string): Promise<void> {
    const caller = currentUser(req);
    await this.readGate.authorizeRead(channelId, caller.id, true);
    await this.unread.reset(caller.id, channelId);
    res.status(204).send();
  }
}
