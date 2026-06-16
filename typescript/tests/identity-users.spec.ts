// S-ID (identity) + S-US (users): the bootstrap and identity boundary. Every
// non-bootstrap route inherits the identity decision from the middleware, so
// these run through the real assembled app over HTTP.

import { describe, expect, it } from 'vitest';

import { client, clientAt, seedUser } from './helpers.js';
import { getFixture } from './fixture-holder.js';

describe('S-ID identity', () => {
  it('S-ID-01: any endpoint without X-User-Id → 401 identity:missing', async () => {
    const fx = await getFixture();
    const anon = clientAt(fx.baseUrl(), '');
    (await anon.get('/channels')).expect(401).expectCode('identity:missing');
  });

  it('S-ID-02: X-User-Id of a non-existent user → 401 identity:unknown', async () => {
    const ghost = await client('does-not-exist');
    (await ghost.get('/channels')).expect(401).expectCode('identity:unknown');
  });
});

describe('S-US users', () => {
  it('S-US-01: POST /users valid → 201, echoes handle/displayName, id+createdAt present', async () => {
    const anon = clientAt((await getFixture()).baseUrl(), '');
    const resp = (await anon.post('/users', { handle: 'alice', displayName: 'Alice' })).expect(201);
    const u = resp.json<{ id: string; handle: string; displayName: string; createdAt: string }>();
    expect(u.handle).toBe('alice');
    expect(u.displayName).toBe('Alice');
    expect(u.id).toBeTruthy();
    expect(u.createdAt).toBeTruthy();
  });

  it('S-US-02: duplicate handle → 409 user:handle:taken', async () => {
    const anon = clientAt((await getFixture()).baseUrl(), '');
    (await anon.post('/users', { handle: 'dupe', displayName: 'First' })).expect(201);
    (await anon.post('/users', { handle: 'dupe', displayName: 'Second' }))
      .expect(409)
      .expectCode('user:handle:taken');
  });

  it('S-US-03: invalid handle ("ab", "UPPER", "has space") → 422 user:handle:invalid', async () => {
    const anon = clientAt((await getFixture()).baseUrl(), '');
    for (const handle of ['ab', 'UPPER', 'has space']) {
      (await anon.post('/users', { handle, displayName: 'X' }))
        .expect(422)
        .expectCode('user:handle:invalid');
    }
  });

  it('S-US-04: displayName empty / 65 chars → 422 user:display_name:invalid', async () => {
    const anon = clientAt((await getFixture()).baseUrl(), '');
    (await anon.post('/users', { handle: 'dn_empty', displayName: '' }))
      .expect(422)
      .expectCode('user:display_name:invalid');
    (await anon.post('/users', { handle: 'dn_long', displayName: 'x'.repeat(65) }))
      .expect(422)
      .expectCode('user:display_name:invalid');
  });

  it('S-US-05: GET /users/{id} existing → 200', async () => {
    const u = await seedUser('getme');
    const c = await client(u.id);
    (await c.get(`/users/${u.id}`)).expect(200);
  });

  it('S-US-06: GET /users/{id} unknown → 404 user:not_found', async () => {
    const u = await seedUser('seeker');
    const c = await client(u.id);
    (await c.get('/users/nope')).expect(404).expectCode('user:not_found');
  });
});
