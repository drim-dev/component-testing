// Determinism runner (not a test; .mjs is excluded by the vitest include glob).
// Runs the broker-sensitive spec files N consecutive times and reports the
// pass/fail tally — the zero-flake gate evidence. Usage: node tests/.determinism-runner.mjs <N> <file...>
import { spawnSync } from 'node:child_process';

const n = Number(process.argv[2] ?? 10);
const files = process.argv.slice(3);
let passed = 0;
const fails = [];
for (let i = 1; i <= n; i++) {
  const res = spawnSync('npx', ['vitest', 'run', ...files, '--reporter=basic'], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  const out = (res.stdout ?? '') + (res.stderr ?? '');
  const ok = res.status === 0 && /Tests\s+\d+ passed/.test(out) && !/failed/.test(out);
  const m = out.match(/Tests\s+([^\n]+)/);
  console.log(`run ${i}/${n}: ${ok ? 'PASS' : 'FAIL'} — ${m ? m[1].trim() : 'no summary'}`);
  if (ok) {
    passed++;
  } else {
    fails.push(i);
    console.log(out.split('\n').filter((l) => /FAIL|Error|timed out|expected/.test(l)).slice(0, 8).join('\n'));
  }
}
console.log(`\nDETERMINISM RESULT: ${passed}/${n} runs green` + (fails.length ? ` (failed: ${fails.join(',')})` : ''));
