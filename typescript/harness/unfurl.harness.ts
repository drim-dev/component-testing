// The outbound-HTTP harness: a REAL local stub server (not an in-process client
// mock — the timeout, the socket, and the status codes must be real). Seed =
// program the route (200+title / delay > timeout / 500 / connection reset);
// Assert = received-request count (circuit-breaker proof); Reset = clear route +
// counter.

import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';

import type { DependencyHarness } from './dependency-harness.js';

type UnfurlMode = 'ok' | 'delay' | 'error500' | 'reset';

export class UnfurlHarness implements DependencyHarness {
  private server?: Server;
  private baseUrl = '';
  private mode: UnfurlMode = 'ok';
  private title = 'Example';
  private delayMs = 0;
  private requests = 0;

  getBaseUrl(): string {
    return this.baseUrl;
  }

  // requestCount is the number of /unfurl requests the stub received (breaker proof).
  requestCount(): number {
    return this.requests;
  }

  start(): Promise<void> {
    return new Promise((resolve) => {
      this.server = createServer((req, res) => {
        if (!req.url?.startsWith('/unfurl')) {
          res.writeHead(404).end();
          return;
        }
        this.requests++;
        switch (this.mode) {
          case 'delay':
            setTimeout(() => {
              res.writeHead(200, { 'Content-Type': 'application/json' });
              res.end(JSON.stringify({ title: this.title }));
            }, this.delayMs);
            break;
          case 'error500':
            res.writeHead(500).end();
            break;
          case 'reset':
            req.socket.destroy();
            break;
          default:
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ title: this.title }));
        }
      });
      this.server.listen(0, '127.0.0.1', () => {
        const addr = this.server?.address() as AddressInfo;
        this.baseUrl = `http://127.0.0.1:${addr.port}`;
        resolve();
      });
    });
  }

  reset(): Promise<void> {
    this.mode = 'ok';
    this.title = 'Example';
    this.delayMs = 0;
    this.requests = 0;
    return Promise.resolve();
  }

  stop(): Promise<void> {
    return new Promise((resolve) => {
      if (this.server) {
        this.server.close(() => resolve());
      } else {
        resolve();
      }
    });
  }

  // programOk programs a 200 response with the given title.
  programOk(title: string): void {
    this.mode = 'ok';
    this.title = title;
  }

  // programDelay programs a response slower than the 800 ms client timeout.
  programDelay(delayMs: number): void {
    this.mode = 'delay';
    this.delayMs = delayMs;
  }

  // programServerError programs a 500 response.
  programServerError(): void {
    this.mode = 'error500';
  }
}
