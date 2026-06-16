// The canonical FAKE (../../spec/04-dependencies.md §6): nondeterministic, paid,
// external, so the boundary is a deliberate in-process double, not a container.
// Seed = program the next response (canned / empty / oversized); Assert =
// interaction verification — the captured request is where the prompt-injection
// catch lives; Reset = clear responses + captured calls. Hand-rolled on purpose
// (no mocking framework) so the pattern reads cross-language.

import type { SummaryRequest } from '../src/domain/domain.js';
import type { SummaryModel } from '../src/seams/seams.js';
import type { DependencyHarness } from './dependency-harness.js';

export class LlmHarness implements DependencyHarness {
  private programmed: string[] = [];
  private captured: SummaryRequest[] = [];

  start(): Promise<void> {
    return Promise.resolve();
  }

  reset(): Promise<void> {
    this.clear();
    return Promise.resolve();
  }

  stop(): Promise<void> {
    return Promise.resolve();
  }

  // model returns the fake as the app's SummaryModel seam.
  model(): SummaryModel {
    return {
      complete: (req: SummaryRequest): Promise<string> => {
        this.captured.push(req);
        if (this.programmed.length > 0) {
          return Promise.resolve(this.programmed.shift()!);
        }
        return Promise.resolve('(canned summary)');
      },
    };
  }

  // programResponse seeds the next response (FIFO). Unprogrammed → a canned summary.
  programResponse(response: string): void {
    this.programmed.push(response);
  }

  // capturedRequests returns the requests the app made — for interaction verification.
  capturedRequests(): SummaryRequest[] {
    return [...this.captured];
  }

  clear(): void {
    this.programmed = [];
    this.captured = [];
  }
}
