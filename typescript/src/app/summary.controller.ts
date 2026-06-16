import { Body, Controller, HttpCode, Inject, Param, Post, Req } from '@nestjs/common';

import { invalid } from '../apierr/apierr.js';
import { Role } from '../domain/domain.js';
import {
  CHANNEL_ROLE_GATE,
  SUMMARIZER,
  type ChannelRoleGate,
  type Summarizer,
  type SummarySource,
} from '../seams/seams.js';
import { Store } from '../store/store.js';
import { currentUser, type RequestWithUser } from './identity.middleware.js';

const DEFAULT_SUMMARY_MESSAGE_LIMIT = 50;

@Controller()
export class SummaryController {
  constructor(
    private readonly store: Store,
    @Inject(CHANNEL_ROLE_GATE) private readonly roleGate: ChannelRoleGate,
    @Inject(SUMMARIZER) private readonly summarizer: Summarizer,
  ) {}

  @Post('channels/:id/summary')
  @HttpCode(200)
  async getSummary(
    @Req() req: RequestWithUser,
    @Param('id') channelId: string,
    @Body() body: { messageLimit?: number },
  ): Promise<{ summary: string }> {
    const caller = currentUser(req);
    await this.roleGate.authorizeRole(channelId, caller.id, Role.Member);

    let limit = DEFAULT_SUMMARY_MESSAGE_LIMIT;
    if (body.messageLimit !== undefined && body.messageLimit !== null) {
      limit = body.messageLimit;
      if (limit < 1 || limit > 200) {
        throw invalid('summary:message_limit:out_of_range', 'messageLimit must be 1–200.');
      }
    }

    const messages = await this.store.channelMessages(channelId, '', limit);
    if (messages.length === 0) {
      throw invalid('summary:no_messages', 'There is nothing to summarize.');
    }

    // Resolve sender handles, oldest-first (stable, deterministic). The handler
    // only gathers the sources; assembly + output validation live behind the
    // Summarizer seam (G-LLM).
    const sources: SummarySource[] = [];
    for (let i = messages.length - 1; i >= 0; i--) {
      const m = messages[i];
      const u = await this.store.userById(m.senderId);
      sources.push({ handle: u ? u.handle : m.senderId, text: m.text });
    }

    const summary = await this.summarizer.summarize(sources);
    return { summary };
  }
}
