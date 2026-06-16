// Test helpers: an HTTP client that drives the assembled app's REAL boundary as a
// given user (over a real socket — the whole middleware + handler stack, no
// shortcuts), assertion helpers, and seeders that write THROUGH the real
// constraints so seeded states are reachable product states.

import { expect } from 'vitest';

import type { User } from '../src/domain/domain.js';
import { getFixture } from './fixture-holder.js';

export interface ApiUser {
  id: string;
  handle: string;
  displayName: string;
}

// Page mirrors the server's pagination envelope (paging.ts) for test reads.
export interface Page<T> {
  items: T[];
  nextBefore: string | null;
}

// awaitUntil polls an async predicate until it holds or the deadline passes —
// the never-sleep settled assertion (04-dependencies.md §9). It returns the
// satisfying value so callers can assert on it.
export async function awaitUntil<T>(
  probe: () => Promise<T>,
  ok: (value: T) => boolean,
  opts: { deadlineMs?: number; intervalMs?: number; label?: string } = {},
): Promise<T> {
  const deadlineMs = opts.deadlineMs ?? 15_000;
  const intervalMs = opts.intervalMs ?? 100;
  const until = Date.now() + deadlineMs;
  let last: T = await probe();
  for (;;) {
    if (ok(last)) {
      return last;
    }
    if (Date.now() > until) {
      throw new Error(`awaitUntil timed out${opts.label ? ` (${opts.label})` : ''}: last=${JSON.stringify(last)}`);
    }
    await new Promise((r) => setTimeout(r, intervalMs));
    last = await probe();
  }
}

// Response wraps an HTTP reply with assertion helpers.
export class ApiResponse {
  constructor(
    readonly status: number,
    readonly rawBody: string,
    readonly headers: Headers,
  ) {}

  expect(status: number): this {
    expect(this.status, `body: ${this.rawBody}`).toBe(status);
    return this;
  }

  expectCode(code: string): this {
    const body = JSON.parse(this.rawBody) as { code?: string };
    expect(body.code, `raw: ${this.rawBody}`).toBe(code);
    return this;
  }

  json<T>(): T {
    return JSON.parse(this.rawBody) as T;
  }
}

// Client drives an HTTP boundary as a given user.
export class Client {
  constructor(
    private readonly baseUrl: string,
    private readonly userId: string,
  ) {}

  private headers(hasBody: boolean): Record<string, string> {
    const h: Record<string, string> = {};
    if (this.userId !== '') {
      h['X-User-Id'] = this.userId;
    }
    if (hasBody) {
      h['Content-Type'] = 'application/json';
    }
    return h;
  }

  async send(method: string, path: string, body?: unknown): Promise<ApiResponse> {
    const init: RequestInit = { method, headers: this.headers(body !== undefined) };
    if (body !== undefined) {
      init.body = JSON.stringify(body);
    }
    const resp = await fetch(this.baseUrl + path, init);
    const text = await resp.text();
    return new ApiResponse(resp.status, text, resp.headers);
  }

  get(path: string): Promise<ApiResponse> {
    return this.send('GET', path);
  }
  post(path: string, body?: unknown): Promise<ApiResponse> {
    return this.send('POST', path, body);
  }
  del(path: string): Promise<ApiResponse> {
    return this.send('DELETE', path);
  }
}

// BinaryResponse wraps a raw byte reply (attachment download).
export class BinaryResponse {
  constructor(
    readonly status: number,
    readonly bytes: Buffer,
    readonly headers: Headers,
  ) {}

  expect(status: number): this {
    expect(this.status, `len=${this.bytes.length}`).toBe(status);
    return this;
  }

  code(): string | undefined {
    try {
      return (JSON.parse(this.bytes.toString()) as { code?: string }).code;
    } catch {
      return undefined;
    }
  }
}

// uploadAttachment posts a file field through the real multipart boundary.
export async function uploadAttachment(
  baseUrl: string,
  userId: string,
  channelId: string,
  filename: string,
  data: Buffer,
): Promise<ApiResponse> {
  const form = new FormData();
  form.append('file', new Blob([data]), filename);
  const resp = await fetch(`${baseUrl}/channels/${channelId}/attachments`, {
    method: 'POST',
    headers: { 'X-User-Id': userId },
    body: form,
  });
  return new ApiResponse(resp.status, await resp.text(), resp.headers);
}

// downloadAttachment fetches the raw bytes of an attachment as a given user.
export async function downloadAttachment(baseUrl: string, userId: string, attachmentId: string): Promise<BinaryResponse> {
  const headers: Record<string, string> = {};
  if (userId !== '') {
    headers['X-User-Id'] = userId;
  }
  const resp = await fetch(`${baseUrl}/attachments/${attachmentId}`, { headers });
  const buf = Buffer.from(await resp.arrayBuffer());
  return new BinaryResponse(resp.status, buf, resp.headers);
}

// client drives the assembled CORRECT app as userId.
export async function client(userId: string): Promise<Client> {
  const fixture = await getFixture();
  return new Client(fixture.baseUrl(), userId);
}

// clientAt drives an arbitrary base URL (e.g. a naive host) as userId.
export function clientAt(baseUrl: string, userId: string): Client {
  return new Client(baseUrl, userId);
}

// ---- seeders ----

export async function seedUser(handle: string): Promise<ApiUser> {
  const c = await client('');
  const resp = (await c.post('/users', { handle, displayName: handle })).expect(201);
  const u = resp.json<{ id: string; handle: string; displayName: string }>();
  return { id: u.id, handle: u.handle, displayName: u.displayName };
}

export async function seedChannel(owner: ApiUser, name: string, isPrivate: boolean): Promise<string> {
  const c = await client(owner.id);
  const resp = (await c.post('/channels', { name, private: isPrivate })).expect(201);
  return resp.json<{ id: string }>().id;
}

export async function seedMember(by: ApiUser, channelId: string, target: ApiUser): Promise<void> {
  const c = await client(by.id);
  (await c.post(`/channels/${channelId}/members`, { userId: target.id })).expect(201);
}

export async function seedJoin(who: ApiUser, channelId: string): Promise<void> {
  const c = await client(who.id);
  (await c.post(`/channels/${channelId}/join`)).expect(201);
}

export async function seedConversation(a: ApiUser, b: ApiUser): Promise<string> {
  const c = await client(a.id);
  const resp = await c.post('/dm/conversations', { recipientId: b.id });
  if (resp.status !== 201 && resp.status !== 200) {
    throw new Error(`seed conversation: status ${resp.status} (${resp.rawBody})`);
  }
  return resp.json<{ id: string }>().id;
}

export async function seedDmMessage(sender: ApiUser, conversationId: string, text: string): Promise<void> {
  const c = await client(sender.id);
  (await c.post(`/dm/conversations/${conversationId}/messages`, { text })).expect(201);
}

// dbCount is a DB-state assert via the database harness.
export async function dbCount(table: string, where: string, ...args: unknown[]): Promise<number> {
  const fixture = await getFixture();
  return fixture.database.count(table, where, ...args);
}

// toApiUser narrows a domain User to the test's ApiUser shape.
export function toApiUser(u: User): ApiUser {
  return { id: u.id, handle: u.handle, displayName: u.displayName };
}
