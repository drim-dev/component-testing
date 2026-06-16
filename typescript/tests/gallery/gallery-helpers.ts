// Gallery helpers — the §11.D injection mechanism in the Nest idiom. A naive app
// is a SECOND Relay host built from the same live containers with exactly ONE
// provider token overridden by its naive variant; the catching test's own
// assertion block runs against it through expectCatchToFail, which asserts the
// block goes RED (the bug is observable). This keeps a red demonstration runnable
// inside a green suite.

import { naiveApp, type NaiveAppHandle, type SeamOverride } from '../../harness/naive-app.js';
import { Client } from '../helpers.js';
import { getFixture } from '../fixture-holder.js';

// withNaiveApp builds a naive-wired host with the given seam overrides, hands the
// caller a Client factory bound to it, runs the body, and always closes the host.
export async function withNaiveApp(
  overrides: SeamOverride[],
  body: (clientFor: (userId: string) => Client, handle: NaiveAppHandle) => Promise<void>,
): Promise<void> {
  const fx = await getFixture();
  const handle = await naiveApp(fx, overrides);
  try {
    await body((userId) => new Client(handle.baseUrl(), userId), handle);
  } finally {
    await handle.close();
  }
}
