// PresenceStubServer is a canned-response stand-in for the neighbour presence
// service: a real gRPC server on a loopback socket that answers the unary and
// streaming RPCs from an in-memory online set, with a test-only fault that aborts
// the stream after N messages (the deterministic partial-stream probe for G-GRPC).
// No Redis, no neighbour dependencies — just the contract the Relay API consumes.

import * as grpc from '@grpc/grpc-js';

import {
  PresenceService,
  type GetPresenceRequest,
  type PresenceStatusMessage,
  type StreamChannelPresenceRequest,
} from '../src/presence/proto.js';

export class PresenceStubServer {
  private readonly server = new grpc.Server();
  private readonly onlineSet = new Set<string>();
  // 0 = disarmed; an armed value is N+1.
  private failAfter = 0;

  constructor() {
    this.server.addService(PresenceService.service, {
      getPresence: this.getPresence.bind(this),
      streamChannelPresence: this.streamChannelPresence.bind(this),
    });
  }

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

  setOnline(userId: string): void {
    this.onlineSet.add(userId);
  }

  failStreamAfter(n: number): void {
    this.failAfter = n + 1;
  }

  reset(): void {
    this.onlineSet.clear();
    this.failAfter = 0;
  }

  private limit(): number | null {
    return this.failAfter === 0 ? null : this.failAfter - 1;
  }

  private getPresence(
    call: grpc.ServerUnaryCall<GetPresenceRequest, PresenceStatusMessage>,
    cb: grpc.sendUnaryData<PresenceStatusMessage>,
  ): void {
    const userId = call.request.userId;
    cb(null, { userId, online: this.onlineSet.has(userId) });
  }

  private streamChannelPresence(call: grpc.ServerWritableStream<StreamChannelPresenceRequest, PresenceStatusMessage>): void {
    const userIds = call.request.userIds;
    const limit = this.limit();
    for (let i = 0; i < userIds.length; i++) {
      if (limit !== null && i >= limit) {
        call.emit('error', {
          code: grpc.status.UNAVAILABLE,
          details: 'presence stream fault (test-only): aborting mid-stream',
        } as grpc.ServiceError);
        return;
      }
      call.write({ userId: userIds[i], online: this.onlineSet.has(userIds[i]) });
    }
    call.end();
  }
}
