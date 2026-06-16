// The Redis harness (cache / counters / breaker state). Real container.
// Seed = set keys directly (e.g. pre-warm a membership cache to prove
// invalidation); Assert = read keys/TTLs; Reset = FLUSHDB (the trivially fast
// reset — contrast with Postgres's TRUNCATE).

import { RedisContainer, type StartedRedisContainer } from '@testcontainers/redis';
import Redis from 'ioredis';

import type { DependencyHarness } from './dependency-harness.js';
import { REDIS_IMAGE } from './images.js';

export class RedisHarness implements DependencyHarness {
  private container?: StartedRedisContainer;
  private redisClient?: Redis;
  private host = '';
  private port = 0;

  get client(): Redis {
    if (!this.redisClient) {
      throw new Error('RedisHarness not started');
    }
    return this.redisClient;
  }

  get address(): { host: string; port: number } {
    return { host: this.host, port: this.port };
  }

  async start(): Promise<void> {
    this.container = await new RedisContainer(REDIS_IMAGE).start();
    this.host = this.container.getHost();
    this.port = this.container.getMappedPort(6379);
    this.redisClient = new Redis({ host: this.host, port: this.port });
  }

  async reset(): Promise<void> {
    await this.client.flushdb();
  }

  async stop(): Promise<void> {
    if (this.redisClient) {
      this.redisClient.disconnect();
    }
    if (this.container) {
      await this.container.stop();
    }
  }

  // seedMembershipCache pre-warms members:{channelId} so a test can prove
  // invalidation (G-CACHE).
  async seedMembershipCache(channelId: string, ...memberIds: string[]): Promise<void> {
    const key = `members:${channelId}`;
    const pipe = this.client.multi();
    pipe.del(key);
    if (memberIds.length > 0) {
      pipe.sadd(key, ...memberIds);
      pipe.expire(key, 300);
    }
    await pipe.exec();
  }

  // cacheHasMember reports whether the cached set exists and contains a user.
  async cacheHasMember(channelId: string, userId: string): Promise<{ exists: boolean; member: boolean }> {
    const key = `members:${channelId}`;
    const exists = await this.client.exists(key);
    if (exists === 0) {
      return { exists: false, member: false };
    }
    const member = await this.client.sismember(key, userId);
    return { exists: true, member: member === 1 };
  }
}
