import {uiXmlField as field} from './agent-ui-xml.mjs';
// Emulator-only UI input. No retries, history edits, or protocol shortcuts.
// Usage: ADB=/path/to/adb node scripts/send-agent-test-message.mjs emulator-N MARKER [NEW_HARNESS_NAME]
// NEW_HARNESS_NAME guards the English AVD's empty welcome page after switching.
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const shellQuote = value => "'" + value.replaceAll("'", "'\\''") + "'";
const [serial, marker, expectedHarness] = process.argv.slice(2);
const scenarioFile = process.env.OOB_USER_SCENARIO_FILE;
const scenario = scenarioFile ? JSON.parse(readFileSync(scenarioFile, 'utf8')) : null;
const userText = scenario ? `${scenario.prompt.replaceAll('{{MARKER}}', marker)} End your final reply with ${marker}_DONE.`
  : marker === '/compact' ? marker : `Reply ${marker}`;
assert(!scenario || (typeof scenario.prompt === 'string' && scenario.prompt.length < 3000), 'Invalid bounded user scenario');
assert((/^emulator-\d+$/.test(serial || '') ||
  (process.env.OOB_ALLOW_PHYSICAL_DEVICE === '1' && /^[A-Za-z0-9._:-]+$/.test(serial || ''))) &&
  (/^[A-Z][A-Z0-9_]+$/.test(marker || '') || marker === '/compact'),
  'Explicit device and test marker required; physical devices require OOB_ALLOW_PHYSICAL_DEVICE=1');
const adb = (...args) => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args],
  {encoding: 'utf8', timeout: 30000});
const snapshot = () => {
  const path = '/data/local/tmp/oob-send-test.xml';
  assert.match(adb('shell', 'uiautomator', 'dump', path), /dumped to:/);
  return [...adb('shell', 'cat', path).matchAll(/<node\b[^>]*>/g)].map(([n]) => n)
    .filter(n => n.includes('package="cn.com.omnimind.bot"'));
};

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
// Observe readiness after activity/semantics restoration; never replay an action.
let initialNodes;
const readyDeadline = Date.now() + 30000;
do {
  initialNodes = snapshot();
  if (initialNodes.filter(n => field(n, 'class') === 'android.widget.EditText').length === 1) break;
  await new Promise(resolve => setTimeout(resolve, 250));
} while (Date.now() < readyDeadline);
if (expectedHarness) {
  assert(initialNodes.some(n => field(n, 'content-desc').includes(
    `I'm ${expectedHarness}\nI can help you chat, execute, build, and explore.`)),
  'Requested Harness welcome page is not ready; no message entered');
}
const initial = input(initialNodes);
assert.equal(field(initial, 'text'), '', 'Preserve an existing draft');
tap(initial);
assert.equal(field(input(snapshot()), 'focused'), 'true', 'Composer did not gain focus');
// Android input text emits a whole string without waiting for Flutter frames.
// Separate commands avoid losing edge characters on a loaded software-GPU AVD.
// This types once; the exact draft gate below still rejects any dropped input.
for (const character of userText) {
  adb('shell', 'input', 'text', shellQuote(character === ' ' ? '%s' : character));
}
// Dismiss the IME/selection surface before the ONE send tap. Re-read bounds
// and verify the draft afterwards; never replay a submitted prompt.
adb('shell', 'input', 'keyevent', '4');
const ready = snapshot();
assert.equal(field(input(ready), 'text'), userText, 'Draft mismatch; not sending');
const send = ready.filter(n => ['Send', '发送'].includes(field(n, 'content-desc')) &&
  field(n, 'clickable') === 'true' && field(n, 'enabled') === 'true');
assert.equal(send.length, 1, 'Expected one enabled semantic Send control; draft retained');
tap(send[0]);
// A tap is not proof of admission: keyboard/selection overlays can consume it.
// Observe the composer clearing, but never repeat the send automatically.
let accepted = false;
const deadline = Date.now() + 15000;
do {
  try {
    accepted = field(input(snapshot()), 'text') === '';
    if (accepted) break;
  } catch {
    // Only repeat fresh observations during UI transitions.
  }
  await new Promise(resolve => setTimeout(resolve, 500));
} while (Date.now() < deadline);
assert(accepted, 'Send was tapped but composer did not clear; inspect UI before any further action');
console.log(JSON.stringify({serial, marker, sendDispatched: true, replyVerified: false}));
