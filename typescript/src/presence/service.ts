// The companion-owned gRPC presence service (the real transport the G-GRPC catch
// exercises) — booted by the PresenceHarness on an ephemeral loopback port over
// a real socket, so the API consumes it through genuine gRPC (cleartext h2c),
// not an in-process double. Presence lives in Redis under presence:{userId} with
// a 60 s TTL set by the heartbeat; the unary RPC reads one key, the streaming RPC
// emits exactly one status per requested user then closes cleanly.

import * as grpc from '@grpc/grpc-js';
import type Redis from 'ioredis';

import { PRESENCE_KEY_PREFIX } from '../infra/redis-infra.js';
import {
  PresenceService,
  type GetPresenceRequest,
  type PresenceStatusMessage,
  type StreamChannelPresenceRequest,
} from './proto.js';

// StreamFault is test-only fault control (04-dependencies.md §8): armed to "fail
// after N", the streaming RPC writes N statuses then aborts mid-stream with a
// gRPC error — the deterministic partial-stream probe. Unset (the production
// default) → the stream always completes cleanly; the code path is identical.
export class StreamFault {
  // 0 = disarmed; an armed value is N+1.
  private failAfter = 0;

  arm(messages: number): void {
    this.failAfter = messages + 1;
  }

  clear(): void {
    this.failAfter = 0;
  }

  limit(): number | null {
    return this.failAfter === 0 ? null : this.failAfter - 1;
  }
}

export class PresenceServer {
  private readonly server = new grpc.Server();

  constructor(
    private readonly redis: Redis,
    readonly fault: StreamFault,
  ) {
    this.server.addService(PresenceService.service, {
      getPresence: this.getPresence.bind(this),
      streamChannelPresence: this.streamChannelPresence.bind(this),
    });
  }

  // start binds 127.0.0.1:0 (ephemeral) over a real socket and returns the addr.
  start(): Promise<string> {
    return new Promise((resolve, reject) => {
      this.server.bindAsync('127.0.0.1:0', grpc.ServerCredentials.createInsecure(), (err, port) => {
        if (err) {
          reject(err);
          return;
        }
        resolve(`127.0.0.1:${port}`);
      });
    });
  }

  stop(): Promise<void> {
    return new Promise((resolve) => this.server.tryShutdown(() => resolve()));
  }

  private getPresence(
    call: grpc.ServerUnaryCall<GetPresenceRequest, PresenceStatusMessage>,
    cb: grpc.sendUnaryData<PresenceStatusMessage>,
  ): void {
    const userId = call.request.userId;
    this.online(userId).then(
      (online) => cb(null, { userId, online }),
      (err: unknown) => cb(err as grpc.ServiceError),
    );
  }

  private streamChannelPresence(call: grpc.ServerWritableStream<StreamChannelPresenceRequest, PresenceStatusMessage>): void {
    const userIds = call.request.userIds;
    const limit = this.fault.limit();
    void (async () => {
      for (let i = 0; i < userIds.length; i++) {
        if (limit !== null && i >= limit) {
          call.emit('error', {
            code: grpc.status.UNAVAILABLE,
            details: 'presence stream fault (test-only): aborting mid-stream',
          } as grpc.ServiceError);
          return;
        }
        const online = await this.online(userIds[i]);
        call.write({ userId: userIds[i], online });
      }
      call.end();
    })();
  }

  private async online(userId: string): Promise<boolean> {
    return (await this.redis.exists(`${PRESENCE_KEY_PREFIX}${userId}`)) > 0;
  }
}
