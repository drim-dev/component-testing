import { Controller, Get, Inject, Query, Req } from '@nestjs/common';

import { build, buildWithIds, parseLimit } from '../paging/paging.js';
import { UNREAD_COUNTERS, type UnreadCounters } from '../seams/seams.js';
import { Store } from '../store/store.js';
import { currentUser, type RequestWithUser } from './identity.middleware.js';

interface NotificationDto {
  id: string;
  type: string;
  dmMessageId: string;
  conversationId: string;
  senderId: string;
  preview: string;
  createdAt: string;
}

interface FeedEntryDto {
  channelId: string;
  messageId: string;
  senderId: string;
  preview: string;
  createdAt: string;
}

@Controller()
export class FeedController {
  constructor(
    private readonly store: Store,
    @Inject(UNREAD_COUNTERS) private readonly unread: UnreadCounters,
  ) {}

  @Get('notifications')
  async getNotifications(@Req() req: RequestWithUser, @Query('limit') limit?: string, @Query('before') before?: string): Promise<unknown> {
    const caller = currentUser(req);
    const parsed = parseLimit(limit);
    const rows = await this.store.notificationsFor(caller.id, before ?? '', parsed + 1);
    const dtos: NotificationDto[] = rows.map((n) => ({
      id: n.id,
      type: 'dm.message',
      dmMessageId: n.dmMessageId,
      conversationId: n.conversationId,
      senderId: n.senderId,
      preview: n.preview,
      createdAt: n.createdAt.toISOString(),
    }));
    return build(dtos, parsed, (d) => d.id);
  }

  @Get('feed')
  async getFeed(@Req() req: RequestWithUser, @Query('limit') limit?: string, @Query('before') before?: string): Promise<unknown> {
    const caller = currentUser(req);
    const parsed = parseLimit(limit);
    const rows = await this.store.feedFor(caller.id, before ?? '', parsed + 1);
    const dtos: FeedEntryDto[] = rows.map((f) => ({
      channelId: f.channelId,
      messageId: f.messageId,
      senderId: f.senderId,
      preview: f.preview,
      createdAt: f.createdAt.toISOString(),
    }));
    // The feed cursor (feed_entries.id) is not part of the DTO shape, so it is
    // carried alongside.
    return buildWithIds(dtos, rows.map((f) => f.id), parsed);
  }

  @Get('me/unread')
  async getUnread(@Req() req: RequestWithUser): Promise<{ channels: Record<string, number> }> {
    const caller = currentUser(req);
    const counts = await this.unread.forUser(caller.id);
    return { channels: counts };
  }
}
