// LYING TESTS (exhibits, do not copy) — the agent's default test style for each
// gallery case. Every test here is real, runnable, and GREEN — that is the point:
// each verifies a MOCK it set up, never the assembled system, so the bug it
// claims to cover is unrepresentable in its universe. Each is paired in the
// README with its catching test (the scenario id) and, for injectable cases, the
// naive red→green demo in naive-demos.spec.ts.
//
// 05-gallery §0.2: every lying test carries `lying` in its file name and opens
// with the header below naming the case + catcher.

import { describe, expect, it, vi } from 'vitest';

import { atLeast, isParticipant, preview, Role, type Conversation } from '../../src/domain/domain.js';
import { parseLimit } from '../../src/paging/paging.js';

describe('Gallery lying tests (exhibits)', () => {
  // LYING TEST (exhibit, do not copy) — gallery case G-IDOR; caught by S-DM-08 (dm.spec.ts) + G-IDOR demo (naive-demos.spec.ts)
  it('G-IDOR lying: stub the access guard to true, then "verify" messages come back', async () => {
    // The security decision is switched off inside the test: a stub returns the
    // conversation regardless of the caller. Green by construction.
    const guard = { getForParticipant: vi.fn().mockResolvedValue({ id: 'c1', userLo: 'a', userHi: 'b' }) };
    const conv = await guard.getForParticipant('c1', 'intruder');
    expect(conv).not.toBeNull();
    expect(guard.getForParticipant).toHaveBeenCalled();
  });

  // LYING TEST (exhibit, do not copy) — gallery case G-BOLA-READ; caught by S-CH-05/S-CH-21 (channels.spec.ts) + G-BOLA-READ demo
  it('G-BOLA-READ lying: stub membership repo to return a membership, assert messages return', async () => {
    const repo = { membership: vi.fn().mockResolvedValue({ role: Role.Member }), messages: vi.fn().mockResolvedValue([{ text: 'm' }]) };
    const m = await repo.membership('priv', 'nonmember');
    expect(m).not.toBeNull();
    expect(await repo.messages('priv')).toHaveLength(1);
  });

  // LYING TEST (exhibit, do not copy) — gallery case G-BOLA-ROLE; caught by S-CH-11/15/19 (channels.spec.ts) + G-BOLA-ROLE demo
  it('G-BOLA-ROLE lying: hand-build an "admin" context, assert the action is allowed', () => {
    // The test constructs the very authority it should verify.
    const ctx = { role: Role.Admin };
    expect(atLeast(ctx.role, Role.Admin)).toBe(true);
  });

  // LYING TEST (exhibit, do not copy) — gallery case G-CACHE; caught by S-CH-16/S-FD-06 (channels/feed.spec.ts) + G-CACHE demo
  it('G-CACHE lying: mock the cache as an in-memory dict the test keeps consistent with the DB', () => {
    // A mock cache cannot diverge from the DB, so the divergence bug is unrepresentable.
    const db = new Set<string>(['a', 'b']);
    const cache = new Set<string>(db); // kept in lock-step by the test itself
    db.delete('b');
    cache.delete('b'); // the test does the invalidation the code forgot
    expect(cache.has('b')).toBe(false);
  });

  // LYING TEST (exhibit, do not copy) — gallery case G-RABBIT; caught by S-NT-02/03/04 (notifications.spec.ts) + G-RABBIT demo
  it('G-RABBIT lying: assert the producer "published exactly once" against a mock broker', () => {
    const publish = vi.fn();
    publish({ dmMessageId: 'm1' });
    expect(publish).toHaveBeenCalledTimes(1); // delivery semantics never execute
  });

  // LYING TEST (exhibit, do not copy) — gallery case G-RACE; caught by S-DM-05 (dm.spec.ts) + G-RACE demo
  it('G-RACE lying: single-threaded "creating twice returns the same conversation"', async () => {
    const store = new Map<string, Conversation>();
    const create = async (lo: string, hi: string): Promise<Conversation> => {
      const key = `${lo}:${hi}`;
      const existing = store.get(key);
      if (existing) {
        return existing;
      }
      const conv: Conversation = { id: 'one', userLo: lo, userHi: hi, createdAt: new Date() };
      store.set(key, conv);
      return conv;
    };
    const first = await create('a', 'b');
    const second = await create('a', 'b'); // sequential — the window never opens
    expect(second.id).toBe(first.id);
  });

  // LYING TEST (exhibit, do not copy) — gallery case G-TX; caught by S-DM-06 (dm.spec.ts) + G-TX demo
  it('G-TX lying: verify-the-call, not the outcome (saveConversation + saveParticipant twice)', () => {
    const repo = { saveConversation: vi.fn(), saveParticipant: vi.fn() };
    repo.saveConversation();
    repo.saveParticipant();
    repo.saveParticipant();
    expect(repo.saveConversation).toHaveBeenCalledTimes(1);
    expect(repo.saveParticipant).toHaveBeenCalledTimes(2); // partial-commit survives green
  });

  // LYING TEST (exhibit, do not copy) — gallery case G-KAFKA; caught by S-FD-01/05 (feed.spec.ts) + G-KAFKA demos
  it('G-KAFKA lying: mocked producer always succeeds; assert feed consistency the mock fabricated', async () => {
    const feed: string[] = [];
    const bus = { publish: vi.fn(async (memberId: string) => void feed.push(memberId)) }; // in-process synchronous "bus"
    await bus.publish('b');
    expect(feed).toEqual(['b']); // instant consistency the real broker never promises
  });

  // LYING TEST (exhibit, do not copy) — gallery case G-S3; caught by S-AT-06/07 (attachments.spec.ts) + G-S3 demo
  it('G-S3 lying: mock the storage client to return bytes, assert the handler returns bytes', async () => {
    const objects = { get: vi.fn().mockResolvedValue(Buffer.from('bytes')) };
    const bytes = await objects.get('any-key'); // authorization dimension is absent from the universe
    expect(bytes.length).toBeGreaterThan(0);
  });

  // LYING TEST (exhibit, do not copy) — gallery case G-LLM; caught by S-SM-03/04/05 (summary.spec.ts) + G-LLM demo
  it('G-LLM lying: mock the model to return "a summary", assert the endpoint returns it', async () => {
    const model = { complete: vi.fn().mockResolvedValue('a summary') };
    const out = await model.complete({ systemPrompt: 'anything', messageBlocks: [] });
    expect(out).toBe('a summary'); // prompt construction + output validation outside the universe
  });

  // LYING TEST (exhibit, do not copy) — gallery case G-HTTP; caught by S-LP-02/03/04 (linkpreview.spec.ts) + G-HTTP demo
  it('G-HTTP lying: mock the HTTP client to return 200 instantly', async () => {
    const http = { get: vi.fn().mockResolvedValue({ status: 200, title: 'Example' }) };
    const resp = await http.get('https://x'); // timeouts, sockets, failures cannot occur here
    expect(resp.title).toBe('Example');
  });

  // LYING TEST (exhibit, do not copy) — gallery case G-GRPC; caught by S-PR-04 (presence.spec.ts) + G-GRPC demo
  it('G-GRPC lying: mock the client to return a fully-materialized list', async () => {
    const presence = { channelPresence: vi.fn().mockResolvedValue([{ userId: 'a', online: true }, { userId: 'b', online: false }]) };
    const list = await presence.channelPresence(['a', 'b']); // streaming + mid-stream failure do not exist
    expect(list).toHaveLength(2);
  });

  // LYING TEST (exhibit, do not copy) — gallery case G-TAUT; caught by S-DM-11 (dm.spec.ts, the real list test)
  it('G-TAUT lying: stub the message repo to return a canned message; assert the service returns it', async () => {
    // The mirror in its purest form: it verifies the stub, not the system.
    const repo = { messages: vi.fn().mockResolvedValue([{ id: 'm1', text: 'canned', senderId: 'a' }]) };
    const out = await repo.messages('conv');
    expect(out[0].text).toBe('canned');
    // The pure helpers the honesty notes DO endorse as unit territory:
    expect(isParticipant({ id: 'c', userLo: 'a', userHi: 'b', createdAt: new Date() }, 'a')).toBe(true);
    expect(preview('x'.repeat(150))).toHaveLength(100);
  });

  // LYING TEST (exhibit, do not copy) — gallery case G-WEAKVAL; caught by S-PG-01..04 (pagination.spec.ts) + the §8 test-lock
  it('G-WEAKVAL lying: assertion weakened to mirror the implementation (the "after" state)', () => {
    // The gaming story: rather than fix the code, the agent rewrites the pin to
    // mirror whatever the implementation returns — here, asserting limit=101 is
    // "accepted" by checking the parser does NOT throw, which it DOES. We show the
    // honest pin instead (validation is exactly where unit tests shine), and the
    // §8 lock is what prevents the rewrite from shipping.
    expect(() => parseLimit('101')).toThrow();
    expect(parseLimit('100')).toBe(100);
    expect(parseLimit(undefined)).toBe(50);
  });
});
