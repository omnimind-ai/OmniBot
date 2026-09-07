// Start the existing, user-visible managed Harness installer from Agent mode.
// Usage: ADB=/path/to/adb node scripts/start-harness-device-install.mjs emulator-N 'Kimi Code'
// The named row must be visible. This reports dispatch only, never acceptance.
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const [serial, name] = process.argv.slice(2);
assert(/^emulator-\d+$/.test(serial || '') && name, 'Explicit emulator and Harness name required');
const adb = (...args) => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args],
  {encoding: 'utf8', timeout: 30000});
const path = '/data/local/tmp/oob-harness-install.xml';
assert.match(adb('shell', 'uiautomator', 'dump', path), /dumped to:/);
const xml = adb('shell', 'cat', path);
const nodes = [...xml.matchAll(/<node\b[^>]*>/g)].map(([node]) => ({
  label: (node.match(/content-desc="([^"]*)"/)?.[1] || '').replaceAll('&#10;', '\n').replaceAll('&amp;', '&'),
  bounds: [...(node.match(/bounds="([^"]*)"/)?.[1] || '').matchAll(/\d+/g)].map(([n]) => Number(n)),
  enabled: node.includes('enabled="true"') && node.includes('clickable="true"'),
}));
assert(nodes.some(n => n.label === 'Agent mode'), 'Open Agent mode first');
const rows = nodes.filter(n => n.label.startsWith(`${name}\n`));
assert.equal(rows.length, 1, 'Named Harness must be uniquely visible');
const [x1,y1,x2,y2] = rows[0].bounds;
const buttons = nodes.filter(n => n.label === 'Install' && n.enabled &&
  n.bounds[0] >= x1 && n.bounds[1] >= y1 && n.bounds[2] <= x2 && n.bounds[3] <= y2);
assert.equal(buttons.length, 1, 'Expected one enabled Install action inside Harness row');
const [a,b,c,d] = buttons[0].bounds;
adb('shell', 'input', 'tap', String(Math.round((a+c)/2)), String(Math.round((b+d)/2)));
console.log(JSON.stringify({serial, harness: name, installDispatched: true, acceptance: 'not verified'}));
