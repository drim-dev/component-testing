// PresenceHarness boots the REAL companion-owned presence gRPC service on an
// ephemeral 127.0.0.1 port over a real socket, so the API consumes it through
// genuine gRPC (cleartext h2c) — the transport-agnostic proof (not an in-process
// double). It shares the suite's Redis so a heartbeat is observable through the
// stream. Seed = set presence keys directly; fault control = arm the stream to
// fail after N (the deterministic partial-stream probe); Reset = clear the fault
// flag (presence keys are cleared by the suite's Redis FLUSHDB).

import Redis from 'ioredis';

import { PRESENCE_KEY_PREFIX } from '../src/infra/redis-infra.js';
import { PresenceServer, StreamFault } from '../src/presence/service.js';
import type { DependencyHarness } from './dependency-harness.js';

export class PresenceHarness implements DependencyHarness {
  private redis?: Redis;
  private server?: PresenceServer;
  private readonly fault = new StreamFault();
  private addr = '';

  constructor(private readonly redisAddress: { host: string; port: number }) {}

  get address(): string {
    return this.addr;
  }

  async start(): Promise<void> {
    this.redis = new Redis({ host: this.redisAddress.host, port: this.redisAddress.port });
    this.server = new PresenceServer(this.redis, this.fault);
    this.addr = await this.server.start();
  }

  reset(): Promise<void> {
    this.fault.clear();
    return Promise.resolve();
  }

  async stop(): Promise<void> {
    if (this.server) {
      await this.server.stop();
    }
    if (this.redis) {
      this.redis.disconnect();
    }
  }

  // setOnline marks a user online directly (the same key the heartbeat writes).
  async setOnline(userId: string): Promise<void> {
    if (!this.redis) {
      throw new Error('PresenceHarness not started');
    }
    await this.redis.set(`${PRESENCE_KEY_PREFIX}${userId}`, '1', 'EX', 60);
  }

  // failStreamAfter arms the partial-stream fault: the next stream emits n
  // statuses then aborts.
  failStreamAfter(n: number): void {
    this.fault.arm(n);
  }
}
