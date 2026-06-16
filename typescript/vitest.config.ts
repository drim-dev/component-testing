import swc from 'unplugin-swc';
import { defineConfig } from 'vitest/config';

// Vitest drives the component suite. SWC compiles with decorator metadata
// (esbuild does not emit it), so Nest's DI reflection works the same in tests
// as at runtime. The suite is SERIAL with a single fork: one Docker host, one
// Testcontainers suite at a time (04-dependencies.md §9) — two parallel suites
// would create false flake and poison the zero-flake gate.
export default defineConfig({
  plugins: [
    swc.vite({
      module: { type: 'es6' },
      jsc: {
        target: 'es2022',
        parser: { syntax: 'typescript', decorators: true },
        transform: { legacyDecorator: true, decoratorMetadata: true },
      },
    }),
  ],
  test: {
    globals: true,
    include: ['tests/**/*.spec.ts'],
    pool: 'forks',
    poolOptions: { forks: { singleFork: true } },
    // isolate:false keeps ONE module graph across all spec files in the single
    // fork, so the fixture-holder singleton (and its Testcontainers composition)
    // is booted exactly once for the whole suite — not re-imported and re-booted
    // per file (which spun up duplicate brokers and raced Kafka's startup).
    isolate: false,
    fileParallelism: false,
    sequence: { concurrent: false },
    setupFiles: ['./tests/setup.ts'],
    hookTimeout: 240_000,
    testTimeout: 60_000,
    teardownTimeout: 120_000,
  },
});
