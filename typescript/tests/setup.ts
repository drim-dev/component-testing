// Per-worker setup (runs in the SAME fork that runs the specs, under singleFork).
// beforeAll ensures the shared fixture is started exactly once (the holder is a
// module singleton); beforeEach resets every dependency to a clean state before
// each scenario; the fixture is stopped once at process exit (Testcontainers'
// Ryuk reaper is the backstop).

import { beforeAll, beforeEach } from 'vitest';

import { getFixture } from './fixture-holder.js';

// beforeAll/beforeEach run per file under singleFork, but getFixture() is an
// idempotent singleton so the containers boot exactly once. The fixture is NOT
// stopped in afterAll (that would fire after the FIRST file and break the rest);
// teardown is the holder's process-exit hook + Testcontainers' Ryuk reaper.
beforeAll(async () => {
  await getFixture();
}, 240_000);

beforeEach(async () => {
  const fixture = await getFixture();
  await fixture.reset();
}, 60_000);
