// The companion-owned gRPC presence service (the neighbour's own production code).
// Presence lives in Redis under presence:{userId} with a 60 s TTL set by the
// heartbeat; the unary RPC reads one key, the streaming RPC emits exactly one
// status per requested user then closes cleanly. In a component test of the Relay
// API this neighbour is stubbed (see harness), not run — this is the real
// implementation it stands in for.

import * as grpc from '@grpc/grpc-js';
import type Redis from 'ioredis';

import { PRESENCE_KEY_PREFIX } from '../infra/redis-infra.js';
import {
  PresenceService,
  type GetPresenceRequest,
  type PresenceStatusMessage,
  type StreamChannelPresenceRequest,
} from './proto.js';

export class PresenceServer {
  private readonly server = new grpc.Server();

  constructor(private readonly redis: Redis) {
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
    void (async () => {
      for (let i = 0; i < userIds.length; i++) {
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
