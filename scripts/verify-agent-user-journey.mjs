#!/usr/bin/env node
// Execute a maintained UI journey on an isolated Android emulator.
// Every action uses current accessibility bounds. No ACP calls, DB writes,
// synthetic replies, retrying sends, or coordinate fallbacks count as UI acceptance.
import {execFileSync} from 'node:child_process';
import {readFileSync, mkdirSync, writeFileSync} from 'node:fs';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import assert from 'node:assert/strict';

const [serial, journeyPath, outputPath] = process.argv.slice(2);
assert((/^emulator-\d+$/.test(serial || '') ||
  (process.env.OOB_ALLOW_PHYSICAL_DEVICE === '1' && /^[A-Za-z0-9._:-]+$/.test(serial || ''))) &&
  journeyPath && outputPath,
  'Usage: verify-agent-user-journey.mjs SERIAL journey.json evidence-directory; physical devices require OOB_ALLOW_PHYSICAL_DEVICE=1');
const scripts = dirname(fileURLToPath(import.meta.url));
const journey = JSON.parse(readFileSync(journeyPath, 'utf8'));
assert(Array.isArray(journey.steps) && journey.steps.length, 'Journey requires steps');
// Old successful history must never satisfy a new run's reply assertion.
const runId = Date.now().toString();
const markers = new Map(journey.steps.filter(s => s.action === 'send')
  .map(s => [s.marker, `${s.marker}_${runId}`]));
for (const step of journey.steps) {
  if (markers.has(step.marker)) step.marker = markers.get(step.marker);
  if (markers.has(step.text)) step.text = markers.get(step.text);
}
const out = resolve(outputPath);
mkdirSync(out, {recursive: true, mode: 0o700});
const adb = (...args) => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args],
  {timeout: 30000, stdio: ['ignore', 'pipe', 'pipe']});
const field = (node, key) => (node.match(new RegExp(`${key}="([^"]*)"`))?.[1] || '')
  .replaceAll('&#10;', '\n').replaceAll('&quot;', '"').replaceAll('&amp;', '&');
function snapshot() {
  assert.match(adb('shell', 'uiautomator', 'dump', '/data/local/tmp/oob-user-journey.xml').toString(),
    /dumped to:/, 'Fresh accessibility snapshot unavailable');
  return [...adb('shell', 'cat', '/data/local/tmp/oob-user-journey.xml').toString()
    .matchAll(/<node\b[^>]*>/g)].map(([n]) => n)
    .filter(n => field(n, 'package') === 'cn.com.omnimind.bot');
}
const label = n => field(n, 'content-desc') || field(n, 'text');
const report = {name: journey.name, serial, runId,
  kind: serial.startsWith('emulator-') ? 'emulator-user-interface' : 'physical-device-user-interface',
  passed: false, steps: []};
let index = 0;
try {
  for (const step of journey.steps) {
    index++;
    const started = Date.now();
    if (step.action === 'tap') {
      execFileSync(process.execPath, [resolve(scripts, 'tap-agent-device-control.mjs'), serial, step.label],
        {timeout: 40000, stdio: ['ignore', 'pipe', 'pipe']});
    } else if (step.action === 'search') {
      assert(/^[A-Za-z0-9._-]+$/.test(step.text), 'Search only accepts a non-private model ID');
      const inputs = snapshot().filter(n => field(n, 'class') === 'android.widget.EditText' &&
        (field(n, 'hint') === step.hint ||
          (field(n, 'focused') === 'true' && field(n, 'text') === step.text)) &&
        field(n, 'enabled') === 'true');
      assert.equal(inputs.length, 1, 'Expected one model search input');
      assert(['', step.text].includes(field(inputs[0], 'text')), 'Preserve existing search input');
      const b = [...field(inputs[0], 'bounds').matchAll(/\d+/g)].map(([n]) => Number(n));
      assert.equal(b.length, 4);
      adb('shell', 'input', 'tap', String(Math.round((b[0]+b[2])/2)), String(Math.round((b[1]+b[3])/2)));
      assert(snapshot().some(n => field(n, 'class') === 'android.widget.EditText' && field(n, 'focused') === 'true'),
        'Model search did not gain focus');
      if (!field(inputs[0], 'text')) adb('shell', 'input', 'text', step.text);
      assert(snapshot().some(n => field(n, 'class') === 'android.widget.EditText' &&
        field(n, 'focused') === 'true' && field(n, 'text') === step.text),
        'Search text was not entered correctly');
    } else if (step.action === 'send') {
      execFileSync(process.execPath, [resolve(scripts, 'send-agent-test-message.mjs'), serial, step.marker],
        {timeout: 90000, stdio: ['ignore', 'pipe', 'pipe']});
    } else if (step.action === 'expect' || step.action === 'reply') {
      const deadline = Date.now() + (step.timeoutMs || 120000);
      let found = false;
      let snapshotFailures = 0;
      do {
        let nodes;
        try {
          nodes = snapshot();
        } catch {
          // UIAutomator can be unavailable during window transitions. Repeat
          // observation only; never repeat a tap or send or reuse stale XML.
          snapshotFailures++;
          await new Promise(r => setTimeout(r, 750));
          continue;
        }
        // A user bubble says "Reply MARKER". Only a separate exact line in
        // an assistant item qualifies, and the Send control must be idle.
        const matching = nodes.filter(n => label(n).split('\n').includes(step.text) &&
          !label(n).includes(`Reply ${step.text}`));
        const running = nodes.some(n => /^(Stop|停止|停止生成)(\n|$)/.test(label(n)));
        found = matching.length > 0 && (step.action !== 'reply' || !running);
        if (found) break;
        await new Promise(r => setTimeout(r, 750));
      } while (Date.now() < deadline);
      assert(found, `Expected visible result did not appear before deadline (${snapshotFailures} unavailable snapshots)`);
    } else if (step.action === 'restart') {
      assert(!snapshot().some(n => /^(Stop|停止|停止生成)(\n|$)/.test(label(n))),
        'Cannot restart during an active turn');
      adb('shell', 'am', 'force-stop', 'cn.com.omnimind.bot');
      adb('shell', 'am', 'start', '-n', 'cn.com.omnimind.bot/.activity.LauncherActivity');
    } else {
      throw new Error('Unknown journey action');
    }
    writeFileSync(resolve(out, `${index}.png`), adb('exec-out', 'screencap', '-p'), {mode: 0o600});
    report.steps.push({index, action: step.action, marker: step.marker,
      passed: true, elapsedMs: Date.now() - started});
    console.log(JSON.stringify(report.steps.at(-1)));
  }
  report.passed = true;
} catch (error) {
  report.steps.push({index, passed: false, errorType: error.name,
    assertionSite: error.stack?.split('\n').find(line => line.includes('verify-agent-user-journey.mjs:'))?.trim()});
  process.exitCode = 1;
} finally {
  writeFileSync(resolve(out, 'result.json'), JSON.stringify(report, null, 2), {mode: 0o600});
  console.log(JSON.stringify({name: journey.name, passed: report.passed, evidenceDirectory: out}));
}
