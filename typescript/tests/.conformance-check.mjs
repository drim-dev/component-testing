// Conformance check (not a test; .mjs is outside the vitest include glob). It is
// the mechanical gate of 06-acceptance.md: the scenario suite must contain a test
// whose NAME embeds each of the 83 ids exactly once — no missing id, no id
// claimed twice. Lying tests and naive demos reference ids too, so this scans
// only the scenario specs (the 1:1 surface). Usage: node tests/.conformance-check.mjs
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const testsDir = dirname(fileURLToPath(import.meta.url));

// The expected catalog: area → count (06-acceptance.md conformance checklist).
const EXPECTED = { 'S-ID': 2, 'S-US': 6, 'S-PG': 5, 'S-DM': 12, 'S-CH': 24, 'S-AT': 7, 'S-NT': 5, 'S-FD': 6, 'S-LP': 5, 'S-PR': 5, 'S-SM': 6 };

// Scenario spec files only (exclude tests/gallery/* and tests/naive/*).
const scenarioFiles = readdirSync(testsDir)
  .filter((f) => f.endsWith('.spec.ts'))
  .map((f) => join(testsDir, f));

const idRe = /\b(S-(?:ID|US|PG|DM|CH|AT|NT|FD|LP|PR|SM)-\d+)\b/g;
const counts = new Map();
for (const file of scenarioFiles) {
  const text = readFileSync(file, 'utf8');
  // Count an id once per test whose `it(...)` name embeds it (the test name is
  // the conformance unit). Each scenario is one `it('S-XX-NN: ...')`.
  for (const line of text.split('\n')) {
    if (!/\bit\(/.test(line)) {
      continue;
    }
    const m = line.match(idRe);
    if (m) {
      for (const id of new Set(m)) {
        counts.set(id, (counts.get(id) ?? 0) + 1);
      }
    }
  }
}

let total = 0;
const missing = [];
const dupes = [];
for (const [area, n] of Object.entries(EXPECTED)) {
  for (let i = 1; i <= n; i++) {
    const id = `${area}-${String(i).padStart(2, '0')}`;
    const c = counts.get(id) ?? 0;
    total++;
    if (c === 0) {
      missing.push(id);
    } else if (c > 1) {
      dupes.push(`${id} (${c}×)`);
    }
  }
}

// Any id present that is NOT in the expected catalog = an extra claiming an id.
const expectedIds = new Set();
for (const [area, n] of Object.entries(EXPECTED)) {
  for (let i = 1; i <= n; i++) {
    expectedIds.add(`${area}-${String(i).padStart(2, '0')}`);
  }
}
const extras = [...counts.keys()].filter((id) => !expectedIds.has(id));

console.log(`expected ${total} scenarios; found ${counts.size} distinct ids across ${scenarioFiles.length} scenario files`);
if (missing.length) {
  console.log('MISSING:', missing.join(', '));
}
if (dupes.length) {
  console.log('DUPLICATED:', dupes.join(', '));
}
if (extras.length) {
  console.log('UNEXPECTED EXTRA IDS:', extras.join(', '));
}
const ok = missing.length === 0 && dupes.length === 0 && extras.length === 0 && counts.size === total;
console.log(`CONFORMANCE: ${ok ? 'PASS — 83/83 ids 1:1' : 'FAIL'}`);
process.exit(ok ? 0 : 1);
