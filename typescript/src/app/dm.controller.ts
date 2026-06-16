import { Body, Controller, Get, Inject, Param, Post, Query, Req, Res } from '@nestjs/common';
import type { Response } from 'express';

import { invalid, notFound, notFoundConversation } from '../apierr/apierr.js';
import type { Conversation, DmMessage } from '../domain/domain.js';
import { normalizePair, preview } from '../domain/domain.js';
import { IdFactory } from '../idgen/idgen.js';
import { build, errUnknownBefore, parseLimit } from '../paging/paging.js';
import {
  DM_ACCESS,
  NOTIFICATION_JOBS,
  CONVERSATION_WRITER,
  type DmAccess,
  type NotificationJobs,
  type ConversationWriter,
} from '../seams/seams.js';
import { Store } from '../store/store.js';
import { currentUser, type RequestWithUser } from './identity.middleware.js';

interface ConversationDto {
  id: string;
  participantIds: string[];
  createdAt: string;
}
function toConversationDto(c: Conversation): ConversationDto {
  return { id: c.id, participantIds: [c.userLo, c.userHi], createdAt: c.createdAt.toISOString() };
}

interface DmMessageDto {
  id: string;
  conversationId: string;
  senderId: string;
  text: string;
  createdAt: string;
}

@Controller()
export class DmController {
  constructor(
    private readonly store: Store,
    private readonly ids: IdFactory,
    @Inject(DM_ACCESS) private readonly dmAccess: DmAccess,
    @Inject(CONVERSATION_WRITER) private readonly writer: ConversationWriter,
    @Inject(NOTIFICATION_JOBS) private readonly jobs: NotificationJobs,
  ) {}

  @Post('dm/conversations')
  async createConversation(
    @Req() req: RequestWithUser,
    @Res() res: Response,
    @Body() body: { recipientId?: string },
  ): Promise<void> {
    const caller = currentUser(req);
    const recipientId = body.recipientId ?? '';
    if (recipientId === caller.id) {
      throw invalid('dm:recipient:self', 'You cannot open a conversation with yourself.');
    }
    const recipient = await this.store.userById(recipientId);
    if (!recipient) {
      throw notFound('user:not_found', 'User not found.');
    }
    const [lo, hi] = normalizePair(caller.id, recipient.id);
    const result = await this.writer.create(lo, hi);
    res.status(result.created ? 201 : 200).json(toConversationDto(result.conversation));
  }

  @Get('dm/conversations')
  async listConversations(@Req() req: RequestWithUser, @Query('limit') limit?: string, @Query('before') before?: string): Promise<unknown> {
    const caller = currentUser(req);
    const parsed = parseLimit(limit);
    const cursor = before ?? '';
    if (cursor !== '' && !(await this.store.conversationExists(cursor))) {
      errUnknownBefore();
    }
    const rows = await this.store.conversationsFor(caller.id, cursor, parsed + 1);
    return build(rows.map(toConversationDto), parsed, (d) => d.id);
  }

  @Get('dm/conversations/:id')
  async getConversation(@Req() req: RequestWithUser, @Param('id') id: string): Promise<ConversationDto> {
    const caller = currentUser(req);
    const conv = await this.dmAccess.getForParticipant(id, caller.id);
    if (!conv) {
      throw notFoundConversation();
    }
    return toConversationDto(conv);
  }

  @Post('dm/conversations/:id/messages')
  async createDmMessage(
    @Req() req: RequestWithUser,
    @Res() res: Response,
    @Param('id') conversationId: string,
    @Body() body: { text?: string },
  ): Promise<void> {
    const caller = currentUser(req);
    const conv = await this.dmAccess.getForParticipant(conversationId, caller.id);
    if (!conv) {
      throw notFoundConversation();
    }
    const text = body.text ?? '';
    if (text.length < 1 || [...text].length > 4000) {
      throw invalid('message:text:invalid', 'text must be 1–4000 chars.');
    }
    const msg: DmMessage = {
      id: this.ids.create(),
      conversationId,
      senderId: caller.id,
      text,
      createdAt: new Date(),
    };
    await this.store.insertDmMessage(msg);

    // Pinned ordering (02-api.md §2): the notification job is enqueued AFTER the
    // message transaction commits, awaiting the broker's publisher confirmation.
    // A publish failure here is a 500 — the message stays.
    const recipient = conv.userLo === caller.id ? conv.userHi : conv.userLo;
    await this.jobs.enqueue({
      dmMessageId: msg.id,
      conversationId,
      senderId: caller.id,
      recipientId: recipient,
      preview: preview(text),
    });
    const dto: DmMessageDto = {
      id: msg.id,
      conversationId,
      senderId: caller.id,
      text,
      createdAt: msg.createdAt.toISOString(),
    };
    res.status(201).json(dto);
  }

  @Get('dm/conversations/:id/messages')
  async getDmMessages(
    @Req() req: RequestWithUser,
    @Param('id') conversationId: string,
    @Query('limit') limit?: string,
    @Query('before') before?: string,
  ): Promise<unknown> {
    const caller = currentUser(req);
    const conv = await this.dmAccess.getForParticipant(conversationId, caller.id);
    if (!conv) {
      throw notFoundConversation();
    }
    const parsed = parseLimit(limit);
    const cursor = before ?? '';
    if (cursor !== '' && !(await this.store.dmMessageExists(conversationId, cursor))) {
      errUnknownBefore();
    }
    const rows = await this.store.dmMessages(conversationId, cursor, parsed + 1);
    const dtos: DmMessageDto[] = rows.map((m) => ({
      id: m.id,
      conversationId: m.conversationId,
      senderId: m.senderId,
      text: m.text,
      createdAt: m.createdAt.toISOString(),
    }));
    return build(dtos, parsed, (d) => d.id);
  }
}
