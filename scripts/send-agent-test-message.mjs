// Emulator-only UI input. No retries, history edits, or protocol shortcuts.
// Usage: ADB=/path/to/adb node scripts/send-agent-test-message.mjs emulator-N MARKER [NEW_HARNESS_NAME]
// NEW_HARNESS_NAME guards the English AVD's empty welcome page after switching.
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const [serial, marker, expectedHarness] = process.argv.slice(2);
assert((/^emulator-\d+$/.test(serial || '') ||
  (process.env.OOB_ALLOW_PHYSICAL_DEVICE === '1' && /^[A-Za-z0-9._:-]+$/.test(serial || ''))) &&
  /^[A-Z][A-Z0-9_]+$/.test(marker || ''),
  'Explicit device and test marker required; physical devices require OOB_ALLOW_PHYSICAL_DEVICE=1');
const adb = (...args) => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args],
  {encoding: 'utf8', timeout: 30000});
const snapshot = () => {
  const path = '/data/local/tmp/oob-send-test.xml';
  assert.match(adb('shell', 'uiautomator', 'dump', path), /dumped to:/);
  return [...adb('shell', 'cat', path).matchAll(/<node\b[^>]*>/g)].map(([n]) => n)
    .filter(n => n.includes('package="cn.com.omnimind.bot"'));
};
const field = (n, key) => n.match(new RegExp(`${key}="([^"]*)"`))?.[1] || '';
const input = nodes => {
  const matches = nodes.filter(n => field(n, 'class') === 'android.widget.EditText');
  assert.equal(matches.length, 1, 'Expected one composer');
  return matches[0];
};
const tap = n => {
  const b = [...field(n, 'bounds').matchAll(/\d+/g)].map(([v]) => Number(v));
  assert(b.length === 4 && b[2] > b[0] && b[3] > b[1], 'Invalid visible bounds');
  adb('shell', 'input', 'tap', String(Math.round((b[0] + b[2]) / 2)),
    String(Math.round((b[1] + b[3]) / 2)));
};
const initialNodes = snapshot();
if (expectedHarness) {
  assert(initialNodes.some(n => field(n, 'content-desc').includes(
    `I'm ${expectedHarness}&#10;I can help you chat, execute, build, and explore.`)),
  'Requested Harness welcome page is not ready; no message entered');
}
const initial = input(initialNodes);
assert.equal(field(initial, 'text'), '', 'Preserve an existing draft');
tap(initial);
assert.equal(field(input(snapshot()), 'focused'), 'true', 'Composer did not gain focus');
// Android input text emits a whole string without waiting for Flutter frames.
// Separate commands avoid losing edge characters on a loaded software-GPU AVD.
// This types once; the exact draft gate below still rejects any dropped input.
for (const character of `Reply ${marker}`) {
  adb('shell', 'input', 'text', character === ' ' ? '%s' : character);
}
const ready = snapshot();
assert.equal(field(input(ready), 'text'), `Reply ${marker}`, 'Draft mismatch; not sending');
const send = ready.filter(n => ['Send', '发送'].includes(field(n, 'content-desc')) &&
  field(n, 'clickable') === 'true' && field(n, 'enabled') === 'true');
assert.equal(send.length, 1, 'Expected one enabled semantic Send control; draft retained');
tap(send[0]);
console.log(JSON.stringify({serial, marker, sendDispatched: true, replyVerified: false}));
