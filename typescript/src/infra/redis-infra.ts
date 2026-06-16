// Redis-backed ports: the membership cache (its coherence with Postgres is the
// G-CACHE property), per-channel unread counters, and heartbeats (writes the
// same presence key the gRPC service reads). These are the "correct" wiring the
// module assembles; their Redis client is injected.

import type Redis from 'ioredis';

import type { Heartbeats, MembershipCache, UnreadCounters } from '../seams/seams.js';

export const PRESENCE_KEY_PREFIX = 'presence:';
const MEMBERSHIP_TTL_SECONDS = 300;
const PRESENCE_TTL_SECONDS = 60;

const membersKey = (channelId: string): string => `members:${channelId}`;
const unreadKey = (userId: string, channelId: string): string => `unread:${userId}:${channelId}`;

export class RedisMembershipCache implements MembershipCache {
  constructor(private readonly client: Redis) {}

  async isMember(channelId: string, userId: string): Promise<{ cached: boolean; member: boolean }> {
    const key = membersKey(channelId);
    const exists = await this.client.exists(key);
    if (exists === 0) {
      return { cached: false, member: false };
    }
    const member = await this.client.sismember(key, userId);
    return { cached: true, member: member === 1 };
  }

  async remember(channelId: string, memberIds: string[]): Promise<void> {
    const key = membersKey(channelId);
    const pipe = this.client.multi();
    pipe.del(key);
    if (memberIds.length > 0) {
      pipe.sadd(key, ...memberIds);
      pipe.expire(key, MEMBERSHIP_TTL_SECONDS);
    }
    await pipe.exec();
  }

  async invalidate(channelId: string): Promise<void> {
    await this.client.del(membersKey(channelId));
  }
}

export class RedisUnreadCounters implements UnreadCounters {
  constructor(private readonly client: Redis) {}

  async increment(userId: string, channelId: string): Promise<void> {
    await this.client.incr(unreadKey(userId, channelId));
  }

  async reset(userId: string, channelId: string): Promise<void> {
    await this.client.del(unreadKey(userId, channelId));
  }

  async forUser(userId: string): Promise<Record<string, number>> {
    const prefix = `unread:${userId}:`;
    const out: Record<string, number> = {};
    let cursor = '0';
    do {
      const [next, keys] = await this.client.scan(cursor, 'MATCH', `${prefix}*`, 'COUNT', 100);
      cursor = next;
      for (const key of keys) {
        const channelId = key.slice(prefix.length);
        const raw = await this.client.get(key);
        out[channelId] = raw === null ? 0 : Number.parseInt(raw, 10);
      }
    } while (cursor !== '0');
    return out;
  }
}

export class RedisHeartbeats implements Heartbeats {
  constructor(private readonly client: Redis) {}

  async mark(userId: string): Promise<void> {
    await this.client.set(`${PRESENCE_KEY_PREFIX}${userId}`, '1', 'EX', PRESENCE_TTL_SECONDS);
  }
}
