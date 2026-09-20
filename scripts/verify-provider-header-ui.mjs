// Start on the isolated ExecutionAudit Provider settings page (English emulator).
// Configure only that synthetic Provider to http://127.0.0.1:18781/v1 first.
// UI edits are real; the debug probe only reads the persisted Provider afterward.
import assert from 'node:assert/strict';
import {execFile, execFileSync} from 'node:child_process';
import {promisify} from 'node:util';
import {mkdirSync, writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import http from 'node:http';
import {uiXmlField as field} from './agent-ui-xml.mjs';

const [serial, output] = process.argv.slice(2);
assert(/^emulator-\d+$/.test(serial || '') && output);
const out = resolve(output);
mkdirSync(out, {recursive: true});
const pkg = 'cn.com.omnimind.bot';
const adb = (...args) => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args],
  {encoding: 'utf8', timeout: 30000});
const adbAsync = (...args) => promisify(execFile)(process.env.ADB || 'adb', ['-s', serial, ...args],
  {encoding: 'utf8', timeout: 30000});
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const events = [];
let phase = 'setup';
const server = http.createServer(async (request, response) => {
  for await (const chunk of request) { /* discard synthetic availability probe */ }
  events.push({phase, path: request.url,
    auditHeaderPresent: request.headers['x-oob-audit'] === 'synthetic-header'});
  response.writeHead(200, {'content-type': 'application/json'});
  response.end(JSON.stringify(request.url.endsWith('/models')
    ? {data: [{id: 'gpt-4o'}]} : {choices: [{message: {content: 'OK'}}]}));
});
const snapshot = () => {
  assert.match(adb('shell', 'uiautomator', 'dump', '/data/local/tmp/oob-header-ui.xml'), /dumped to:/);
  return [...adb('shell', 'cat', '/data/local/tmp/oob-header-ui.xml').matchAll(/<node\b[^>]*>/g)]
    .map(([n]) => n).filter(n => field(n, 'package') === pkg);
};
const label = n => field(n, 'content-desc') || field(n, 'text');
function tapNode(n) {
  const b = [...field(n, 'bounds').matchAll(/\d+/g)].map(([v]) => Number(v));
  assert(b.length === 4 && b[2] > b[0] && b[3] > b[1]);
  adb('shell', 'input', 'tap', String(Math.round((b[0]+b[2])/2)), String(Math.round((b[1]+b[3])/2)));
}
async function tap(text) {
  const matches = snapshot().filter(n => label(n).split('\n')[0] === text &&
    field(n, 'enabled') === 'true' && field(n, 'clickable') === 'true');
  assert.equal(matches.length, 1, `Expected one control: ${text}`);
  tapNode(matches[0]); await pause(500);
}
async function enter(name, value) {
  const nodes = snapshot();
  const header = nodes.find(n => label(n).split('\n')[0] === 'Custom Headers');
  assert(header, 'Header editor must be visible');
  const y = n => Number(field(n, 'bounds').match(/^\[\d+,(\d+)\]/)[1]);
  // Flutter currently omits these two visible field labels from UIAutomator.
  // Use the observed two-field editor below its named section, never fixed taps.
  const inputs = nodes.filter(n => field(n, 'class') === 'android.widget.EditText' && y(n) > y(header));
  assert.equal(inputs.length, 2, 'Expected exactly one header name/value editor');
  const input = inputs[name === 'Header Name' ? 0 : 1];
  assert.equal(field(input, 'text'), '', 'Preserve existing configuration');
  tapNode(input);
  adb('shell', 'input', 'text', value);
  adb('shell', 'input', 'keyevent', '4');
  await pause(800);
}
async function probe(expected) {
  const before = events.length;
  await adbAsync('shell', 'am', 'broadcast', '-n', `${pkg}/.debug.DebugModelProviderConfigReceiver`,
    '--es', 'operation', 'verify_bound_provider', '--es', 'profileId', 'oob-execution-audit', '--es', 'modelId', 'gpt-4o');
  const deadline = Date.now() + 30000;
  while (events.slice(before).filter(e => !e.path.endsWith('/models')).length < 3 && Date.now() < deadline) await pause(250);
  const requests = events.slice(before).filter(e => !e.path.endsWith('/models'));
  assert.equal(requests.length, 3, 'Expected three persisted-configuration wire probes');
  assert(requests.every(e => e.auditHeaderPresent === expected), `Persisted header mismatch in ${phase}`);
}
const report = {serial, scope: 'emulator Provider UI and persisted outgoing headers', passed: false, events};
try {
  await new Promise(resolve => server.listen(18781, '127.0.0.1', resolve));
  adb('reverse', 'tcp:18781', 'tcp:18781');
  const initial = snapshot();
  assert(initial.some(n => label(n).includes('ExecutionAudit')), 'Synthetic Provider must be selected');
  assert(initial.some(n => field(n, 'text') === 'http://127.0.0.1:18781/v1'), 'Only the loopback test Provider may be edited');
  await tap('Custom Headers');
  await tap('Add');
  await enter('Header Name', 'X-OOB-Audit');
  await enter('Header Value', 'synthetic-header');
  // Dismissing Android's keyboard leaves the TextField focused. Move focus to
  // the next control so the existing focus-loss autosave actually runs.
  adb('shell', 'input', 'keyevent', '61');
  phase = 'added'; await pause(1000); await probe(true);
  await tap('Delete');
  phase = 'deleted'; await pause(1000); await probe(false);
  await tap('Add');
  await enter('Header Name', 'X-OOB-Audit');
  await enter('Header Value', 'synthetic-header');
  adb('shell', 'input', 'keyevent', '61');
  phase = 'added-before-reopen'; await pause(1000); await probe(true);
  adb('shell', 'input', 'keyevent', '4');
  await pause(700);
  await tap('Model Providers');
  await tap('Custom Headers');
  assert(snapshot().some(n => label(n).includes('Saved headers (values hidden)')),
    'Reopening must distinguish saved redacted headers from absent headers');
  await tap('Clear saved headers');
  phase = 'cleared-after-reopen'; await pause(1000); await probe(false);
  adb('shell', 'am', 'force-stop', pkg);
  adb('shell', 'am', 'start', '-W', '-n', `${pkg}/.activity.LauncherActivity`);
  await pause(1500);
  phase = 'restarted'; await probe(false);
  report.passed = true;
} catch (error) {
  report.error = error.message;
  throw error;
} finally {
  writeFileSync(resolve(out, 'result.json'), JSON.stringify(report, null, 2) + '\n');
  adb('reverse', '--remove', 'tcp:18781');
  server.closeAllConnections(); server.close();
}
console.log(JSON.stringify(report));
