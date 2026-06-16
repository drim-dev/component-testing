// The correct G-HTTP seam: a REAL HTTP client with an 800 ms timeout that
// degrades gracefully (timeout / 5xx / network error → null title, never
// escapes), behind a circuit breaker (5 consecutive failures → skip the call for
// 30 s). Breaker state lives in Redis (resettable by the harness FLUSHDB). The
// naive variant has no timeout/guard.

import type Redis from 'ioredis';

import type { LinkPreviewer } from '../seams/seams.js';

const UNFURL_TIMEOUT_MS = 800;
const BREAKER_THRESHOLD = 5;
const BREAKER_WINDOW_MS = 30_000;
const BREAKER_FAILURES_KEY = 'unfurl:breaker:failures';
const BREAKER_OPEN_UNTIL_KEY = 'unfurl:breaker:open_until';

export class HttpLinkPreviewer implements LinkPreviewer {
  constructor(
    private readonly redis: Redis,
    private readonly baseUrl: string,
  ) {}

  async preview(target: string): Promise<string | null> {
    if (await this.breakerOpen()) {
      return null; // breaker open: skip the call, degrade to no preview
    }
    try {
      const title = await this.fetch(target);
      await this.recordSuccess();
      return title;
    } catch {
      await this.recordFailure();
      return null; // graceful degradation — never surface the upstream failure
    }
  }

  private async fetch(target: string): Promise<string> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), UNFURL_TIMEOUT_MS);
    try {
      const endpoint = `${this.baseUrl}/unfurl?url=${encodeURIComponent(target)}`;
      const resp = await fetch(endpoint, { signal: controller.signal });
      if (resp.status < 200 || resp.status >= 300) {
        throw new Error(`unfurl upstream returned ${resp.status}`);
      }
      const payload = (await resp.json()) as { title?: string };
      return payload.title ?? '';
    } finally {
      clearTimeout(timer);
    }
  }

  private async breakerOpen(): Promise<boolean> {
    const raw = await this.redis.get(BREAKER_OPEN_UNTIL_KEY);
    if (raw === null) {
      return false;
    }
    return Number.parseInt(raw, 10) > Date.now();
  }

  private async recordSuccess(): Promise<void> {
    await this.redis.del(BREAKER_FAILURES_KEY);
  }

  private async recordFailure(): Promise<void> {
    const failures = await this.redis.incr(BREAKER_FAILURES_KEY);
    if (failures >= BREAKER_THRESHOLD) {
      const openUntil = Date.now() + BREAKER_WINDOW_MS;
      await this.redis.set(BREAKER_OPEN_UNTIL_KEY, String(openUntil), 'PX', BREAKER_WINDOW_MS);
    }
  }
}
