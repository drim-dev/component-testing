// PresenceHarness boots a STUB presence gRPC server on an ephemeral 127.0.0.1
// port over a real socket, so the API still consumes presence through genuine
// gRPC (cleartext h2c) — the transport-agnostic proof — without dragging the
// neighbour's own dependencies (its Redis) into the test. Presence is a NEIGHBOUR
// service, so in a component test of the Relay API it is stubbed, not run for
// real. setOnline = program the canned answer; failStreamAfter = arm the
// partial-stream fault; reset = clear the online set and the fault flag.

import { PresenceStubServer } from './presence-stub.js';
import type { DependencyHarness } from './dependency-harness.js';

export class PresenceHarness implements DependencyHarness {
  private readonly stub = new PresenceStubServer();
  private addr = '';

  get address(): string {
    return this.addr;
  }

  async start(): Promise<void> {
    this.addr = await this.stub.start();
  }

  reset(): Promise<void> {
    this.stub.reset();
    return Promise.resolve();
  }

  async stop(): Promise<void> {
    await this.stub.stop();
  }

  // setOnline programs the stub: mark a user online in its canned answer.
  setOnline(userId: string): void {
    this.stub.setOnline(userId);
  }

  // failStreamAfter arms the partial-stream fault: the next stream emits n
  // statuses then aborts.
  failStreamAfter(n: number): void {
    this.stub.failStreamAfter(n);
  }
}
