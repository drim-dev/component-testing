import { Controller, Get, HttpCode, Inject, Param, Post, Req, Res } from '@nestjs/common';
import type { Response } from 'express';

import { notFound, upstream } from '../apierr/apierr.js';
import {
  CHANNEL_READ_GATE,
  HEARTBEATS,
  PRESENCE_CLIENT,
  type ChannelReadGate,
  type Heartbeats,
  type PresenceClient,
} from '../seams/seams.js';
import { Store } from '../store/store.js';
import { currentUser, type RequestWithUser } from './identity.middleware.js';

function statusOf(online: boolean): string {
  return online ? 'online' : 'offline';
}

@Controller()
export class PresenceController {
  constructor(
    private readonly store: Store,
    @Inject(HEARTBEATS) private readonly heartbeats: Heartbeats,
    @Inject(PRESENCE_CLIENT) private readonly presence: PresenceClient,
    @Inject(CHANNEL_READ_GATE) private readonly readGate: ChannelReadGate,
  ) {}

  @Post('me/heartbeat')
  @HttpCode(204)
  async heartbeat(@Req() req: RequestWithUser): Promise<void> {
    const caller = currentUser(req);
    await this.heartbeats.mark(caller.id);
  }

  @Get('users/:id/presence')
  async getUserPresence(@Param('id') userId: string): Promise<{ userId: string; status: string }> {
    const user = await this.store.userById(userId);
    if (!user) {
      throw notFound('user:not_found', 'User not found.');
    }
    const online = await this.presence.userPresence(userId);
    return { userId, status: statusOf(online) };
  }

  @Get('channels/:id/presence')
  async getChannelPresence(@Req() req: RequestWithUser, @Res() res: Response, @Param('id') channelId: string): Promise<void> {
    const caller = currentUser(req);
    // Same visibility as message-read: member 200; public non-member 403;
    // private/unknown 404.
    await this.readGate.authorizeRead(channelId, caller.id, true);
    const memberIds = (await this.store.memberIdsExcept(channelId, '')).sort();
    const result = await this.presence.channelPresence(memberIds);
    if (result.incomplete) {
      throw upstream('presence:incomplete', 'The presence stream terminated before completion.');
    }
    const members = result.statuses.map((s) => ({ userId: s.userId, status: statusOf(s.online) }));
    res.status(200).json({ members });
  }
}
