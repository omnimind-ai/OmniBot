import {uiXmlField as field, hasComposerSelectionToolbar} from './agent-ui-xml.mjs';
// Emulator-only UI input. No retries, history edits, or protocol shortcuts.
// Usage: ADB=/path/to/adb node scripts/send-agent-test-message.mjs emulator-N MARKER [NEW_HARNESS_NAME]
// NEW_HARNESS_NAME guards the English AVD's empty welcome page after switching.
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
import {readFileSync, writeFileSync, mkdirSync} from 'node:fs';
import {resolve} from 'node:path';
const shellQuote = value => "'" + value.replaceAll("'", "'\\''") + "'";
const [serial, marker, expectedHarness] = process.argv.slice(2);
const localCommand = ['/compact', '/pause', '/resume'].includes(marker);
const scenarioFile = process.env.OOB_USER_SCENARIO_FILE;
const scenario = scenarioFile ? JSON.parse(readFileSync(scenarioFile, 'utf8')) : null;
const userText = scenario ? `${scenario.prompt.replaceAll('{{MARKER}}', marker)} End your final reply with ${marker}_DONE.`
  : localCommand ? marker : `Reply ${marker}`;
assert(!localCommand || !scenario, 'Command checks cannot use a scenario');
assert(!scenario || (typeof scenario.prompt === 'string' && scenario.prompt.length < 3000), 'Invalid bounded user scenario');
assert((/^emulator-\d+$/.test(serial || '') ||
  (process.env.OOB_ALLOW_PHYSICAL_DEVICE === '1' && /^[A-Za-z0-9._:-]+$/.test(serial || ''))) &&
  (/^[A-Z][A-Z0-9_]+$/.test(marker || '') || localCommand),
  'Explicit device and test marker required; physical devices require OOB_ALLOW_PHYSICAL_DEVICE=1');
const adb = (...args) => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args],
  {encoding: 'utf8', timeout: 30000});
const snapshot = (allowUnavailable = false) => {
  const path = '/data/local/tmp/oob-send-test.xml';
  const dump = adb('shell', 'uiautomator', 'dump', path);
  // UiTestAutomationBridge can return an empty root during activity restore.
  // Only admission readiness may wait for this; never retry an input action.
  if (allowUnavailable && !dump.trim()) return [];
  assert.match(dump, /dumped to:/);
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
  initialNodes = snapshot(true);
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
// Focusing may briefly rebuild Flutter semantics. Retry only observation;
// the tap above must remain a single action and typing has not started.
let focusedNodes;
const focusDeadline = Date.now() + 30000;
do {
  focusedNodes = snapshot(true);
  if (focusedNodes.filter(n => field(n, 'class') === 'android.widget.EditText').length === 1) break;
  await new Promise(resolve => setTimeout(resolve, 250));
} while (Date.now() < focusDeadline);
assert.equal(field(input(focusedNodes), 'focused'), 'true', 'Composer did not gain focus');
// Android input text emits a whole string without waiting for Flutter frames.
// Separate commands avoid losing edge characters on a loaded software-GPU AVD.
// This types once; the exact draft gate below still rejects any dropped input.
for (const character of userText) {
  adb('shell', 'input', 'text', shellQuote(character === ' ' ? '%s' : character));
}
// Keep the IME as the user left it. Android Back can leave the activity when
// no IME is present, and keyboard dismissal can invalidate accessible bounds.
let ready = snapshot();
if (hasComposerSelectionToolbar(ready)) {
  // A normal tap in the editor closes its selection menu without submitting.
  tap(input(ready));
  ready = snapshot();
  assert(!hasComposerSelectionToolbar(ready), 'Selection toolbar still blocks Send; no send tap performed');
}
assert.equal(field(input(ready), 'text'), userText, 'Draft mismatch; not sending');
if (process.env.OOB_PREPARE_DRAFT_ONLY === '1') {
  console.log(JSON.stringify({serial, marker, draftPrepared:true, sendDispatched:false}));
  process.exit(0);
}
const sendControls = nodes => nodes.filter(n => ['Send', '发送'].includes(field(n, 'content-desc')) &&
  field(n, 'clickable') === 'true' && field(n, 'enabled') === 'true');
const send = sendControls(ready);
assert.equal(send.length, 1, 'Expected one enabled semantic Send control; draft retained');
console.log(JSON.stringify({serial, marker, sendBounds: field(send[0], 'bounds'),
  composerBounds: field(input(ready), 'bounds'), phase: 'before-send'}));
if (process.env.OOB_SEND_EVIDENCE_DIR) {
  const directory = resolve(process.env.OOB_SEND_EVIDENCE_DIR);
  mkdirSync(directory, {recursive: true, mode: 0o700});
  writeFileSync(resolve(directory, `${marker}-before.xml`), ready.join('\n'), {mode: 0o600});
  writeFileSync(resolve(directory, `${marker}-before.png`),
    execFileSync(process.env.ADB || 'adb', ['-s', serial, 'exec-out', 'screencap', '-p']), {mode: 0o600});
}
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
