// Loads the shared presence proto dynamically (no codegen step). The proto is
// byte-identical across all five languages (04-dependencies.md §8).

import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import * as grpc from '@grpc/grpc-js';
import * as protoLoader from '@grpc/proto-loader';

const here = dirname(fileURLToPath(import.meta.url));
const PROTO_PATH = join(here, 'presence.proto');

const packageDefinition = protoLoader.loadSync(PROTO_PATH, {
  keepCase: false,
  longs: String,
  enums: String,
  defaults: true,
  oneofs: true,
});

const loaded = grpc.loadPackageDefinition(packageDefinition) as unknown as {
  relay: { presence: { v1: { Presence: PresenceServiceConstructor } } };
};

export const PresenceService = loaded.relay.presence.v1.Presence;

export interface GetPresenceRequest {
  userId: string;
}
export interface StreamChannelPresenceRequest {
  userIds: string[];
}
export interface PresenceStatusMessage {
  userId: string;
  online: boolean;
}

export interface PresenceServiceConstructor {
  new (address: string, credentials: grpc.ChannelCredentials): PresenceServiceClient;
  service: grpc.ServiceDefinition;
}

export interface PresenceServiceClient extends grpc.Client {
  getPresence(
    req: GetPresenceRequest,
    cb: (err: grpc.ServiceError | null, res?: PresenceStatusMessage) => void,
  ): void;
  streamChannelPresence(
    req: StreamChannelPresenceRequest,
  ): grpc.ClientReadableStream<PresenceStatusMessage>;
}
