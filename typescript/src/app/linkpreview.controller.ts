import { Controller, Get, Inject, Query } from '@nestjs/common';

import { invalid, upstream } from '../apierr/apierr.js';
import { firstUrl } from '../domain/domain.js';
import { LINK_PREVIEWER, type LinkPreviewer } from '../seams/seams.js';

@Controller()
export class LinkPreviewController {
  constructor(@Inject(LINK_PREVIEWER) private readonly previewer: LinkPreviewer) {}

  // The synchronous unfurl proxy — the only outbound-HTTP critical path
  // (02-api.md §6). Unlike the post-time unfurl, an upstream failure here surfaces
  // as 502 (the caller asked for the title directly), not graceful degradation.
  @Get('links/preview')
  async getLinkPreview(@Query('url') url?: string): Promise<{ title: string }> {
    const target = url ?? '';
    if (target.trim() === '' || firstUrl(target) === '') {
      throw invalid('unfurl:url:invalid', 'A valid http(s) url is required.');
    }
    const title = await this.previewer.preview(target);
    if (title === null) {
      throw upstream('unfurl:upstream_failed', 'The unfurl upstream failed.');
    }
    return { title };
  }
}
