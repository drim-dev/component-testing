// The suite-wide Fixture singleton. Under vitest's singleFork all spec files run
// in ONE worker process, so a module-level promise is shared across every file:
// the fixture (and its containers) start exactly once, on first access, and are
// torn down once at the end. This is how the heavy Testcontainers composition is
// shared without per-file boot.

import { Fixture } from '../harness/fixture.js';

let startPromise: Promise<Fixture> | undefined;
let started: Fixture | undefined;

export async function getFixture(): Promise<Fixture> {
  if (!startPromise) {
    const fixture = new Fixture();
    startPromise = fixture.start().then(() => {
      started = fixture;
      // Best-effort teardown on process exit; Testcontainers' Ryuk reaper is the
      // backstop if the process dies hard.
      process.once('beforeExit', () => {
        void fixture.stop();
      });
      return fixture;
    });
  }
  return startPromise;
}

export function tryFixture(): Fixture | undefined {
  return started;
}

export function setFixture(fixture: Fixture): void {
  started = fixture;
  startPromise = Promise.resolve(fixture);
}

export async function stopFixture(): Promise<void> {
  if (started) {
    await started.stop();
    started = undefined;
    startPromise = undefined;
  }
}
