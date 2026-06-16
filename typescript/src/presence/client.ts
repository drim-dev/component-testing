// The correct G-GRPC seam: it consumes the server stream to its CLEAN
// end-of-stream and reports a mid-stream transport error as incomplete — so the
// handler returns 502 and NEVER a partial member list as complete. The naive
// variant swallows the error and returns whatever arrived.

import * as grpc from '@grpc/grpc-js';

import type { PresenceStatus } from '../domain/domain.js';
import type { PresenceClient, PresenceResult } from '../seams/seams.js';
import { PresenceService, type PresenceServiceClient, type PresenceStatusMessage } from './proto.js';

export class GrpcPresenceClient implements PresenceClient {
  private readonly client: PresenceServiceClient;

  constructor(address: string) {
    this.client = new PresenceService(address, grpc.credentials.createInsecure());
  }

  userPresence(userId: string): Promise<boolean> {
    return new Promise((resolve, reject) => {
      this.client.getPresence({ userId }, (err, res) => {
        if (err) {
          reject(err);
          return;
        }
        resolve(res?.online ?? false);
      });
    });
  }

  channelPresence(userIds: string[]): Promise<PresenceResult> {
    return new Promise((resolve) => {
      const stream = this.client.streamChannelPresence({ userIds });
      const statuses: PresenceStatus[] = [];
      stream.on('data', (msg: PresenceStatusMessage) => {
        statuses.push({ userId: msg.userId, online: msg.online });
      });
      stream.on('end', () => {
        // Clean end-of-stream: the full set arrived.
        resolve({ statuses, incomplete: false });
      });
      stream.on('error', () => {
        // A mid-stream abort means we did NOT reach clean end-of-stream: the list
        // we hold is partial. Surfacing it as complete is the gallery bug — report
        // it incomplete so the handler answers 502.
        resolve({ statuses, incomplete: true });
      });
    });
  }
}
