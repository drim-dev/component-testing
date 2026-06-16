// S-LP link preview / outbound HTTP. The timeout/5xx/breaker scenarios
// (S-LP-02,03,04) are the G-HTTP gallery catches against the REAL stub server
// (real socket, real timeout); their naive demo lives in tests/gallery/.

import { describe, expect, it } from 'vitest';

import { getFixture } from './fixture-holder.js';
import { client, seedChannel, seedUser, type ApiUser } from './helpers.js';

interface ChannelMessageDto {
  id: string;
  linkPreviewTitle: string | null;
}

let seq = 0;
async function memberChannel(tag: string): Promise<{ user: ApiUser; channelId: string }> {
  const user = await seedUser(`lp_${tag}_${(seq++).toString(36)}_${Date.now().toString(36)}`);
  const channelId = await seedChannel(user, `lp-${tag}`, true);
  return { user, channelId };
}

describe('S-LP link preview', () => {
  it('S-LP-01: stub 200 {title:"Example"}; post text with URL → 201 linkPreviewTitle; one stub request with the URL', async () => {
    const fx = await getFixture();
    fx.unfurl.programOk('Example');
    const { user, channelId } = await memberChannel('01');
    const c = await client(user.id);
    const url = 'https://example.com/page';
    const msg = (await c.post(`/channels/${channelId}/messages`, { text: `look at ${url}` })).expect(201).json<ChannelMessageDto>();
    expect(msg.linkPreviewTitle).toBe('Example');
    expect(fx.unfurl.requestCount()).toBe(1);
  });

  it('S-LP-02 [G-HTTP]: stub delay 2 s (> 800 ms timeout) → post 201 within 1.5 s, linkPreviewTitle null', async () => {
    const fx = await getFixture();
    fx.unfurl.programDelay(2000);
    const { user, channelId } = await memberChannel('02');
    const c = await client(user.id);
    const start = Date.now();
    const msg = (await c.post(`/channels/${channelId}/messages`, { text: 'slow https://slow.example.com' }))
      .expect(201)
      .json<ChannelMessageDto>();
    const elapsed = Date.now() - start;
    expect(msg.linkPreviewTitle).toBeNull();
    expect(elapsed, `elapsed ${elapsed}ms`).toBeLessThan(1500);
  });

  it('S-LP-03 [G-HTTP]: stub 500 → post 201, null preview', async () => {
    const fx = await getFixture();
    fx.unfurl.programServerError();
    const { user, channelId } = await memberChannel('03');
    const c = await client(user.id);
    const msg = (await c.post(`/channels/${channelId}/messages`, { text: 'err https://err.example.com' }))
      .expect(201)
      .json<ChannelMessageDto>();
    expect(msg.linkPreviewTitle).toBeNull();
  });

  it('S-LP-04 [G-HTTP]: 5 failures open breaker → 6th post 201 null AND stub request count == 5', async () => {
    const fx = await getFixture();
    fx.unfurl.programServerError();
    const { user, channelId } = await memberChannel('04');
    const c = await client(user.id);
    for (let i = 0; i < 6; i++) {
      const msg = (await c.post(`/channels/${channelId}/messages`, { text: `fail-${i} https://breaker.example.com` }))
        .expect(201)
        .json<ChannelMessageDto>();
      expect(msg.linkPreviewTitle).toBeNull();
    }
    expect(fx.unfurl.requestCount()).toBe(5); // breaker opened after 5; no 6th call
  });

  it('S-LP-05: GET /links/preview?url stub 200 → 200 {title}; stub 500 → 502; missing url → 422', async () => {
    const fx = await getFixture();
    const { user } = await memberChannel('05');
    const c = await client(user.id);

    fx.unfurl.programOk('Direct');
    const ok = (await c.get('/links/preview?url=https://direct.example.com')).expect(200).json<{ title: string }>();
    expect(ok.title).toBe('Direct');

    fx.unfurl.programServerError();
    (await c.get('/links/preview?url=https://fail.example.com')).expect(502).expectCode('unfurl:upstream_failed');

    (await c.get('/links/preview')).expect(422).expectCode('unfurl:url:invalid');
  });
});
