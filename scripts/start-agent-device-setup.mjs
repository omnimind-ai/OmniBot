#!/usr/bin/env node
// Re-run setup from the first tutorial page using the choices already saved in UI.
// Does not choose credentials, clear data, or report installation success.
// Usage: ADB=/path/to/adb node scripts/start-agent-device-setup.mjs SERIAL
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const serial = process.argv[2];
assert(serial && !serial.startsWith('-'), 'Explicit device serial required');
const adb = (...args) => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args],
  {encoding: 'utf8', timeout: 20000, stdio: ['ignore', 'pipe', 'pipe']});
for (const label of ['Next', 'Next', 'Start setup']) {
  const report = adb('shell', 'uiautomator', 'dump', '/data/local/tmp/oob-setup-verification.xml');
  assert(report.includes('dumped to:'), 'No fresh UI snapshot; do not act on stale XML');
  const xml = adb('shell', 'cat', '/data/local/tmp/oob-setup-verification.xml');
  const matches = [...xml.matchAll(/<node\b[^>]*>/g)]
    .map(m => m[0]).filter(n => n.includes(`content-desc="${label}"`));
  assert.equal(matches.length, 1, `Expected one visible ${label} control`);
  const bounds = matches[0].match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/);
  assert(bounds, 'Control bounds missing');
  const [, l, t, r, b] = bounds.map(Number);
  assert(r > l && b > t, 'Control is not visible');
  adb('shell', 'input', 'tap', String(Math.round((l+r)/2)), String(Math.round((t+b)/2)));
  console.log(`Activated: ${label}`);
}
console.log('Setup requested. Inspect actual completion/error before claiming success.');
