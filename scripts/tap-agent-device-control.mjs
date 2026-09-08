// Tap one uniquely visible control in the app's test UI, using fresh bounds.
// Usage: ADB=/path/to/adb node scripts/tap-agent-device-control.mjs emulator-N 'Settings'
// The supplied label matches the first line of the accessibility label.
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const [serial, label, parentLabel] = process.argv.slice(2);
assert((/^emulator-\d+$/.test(serial || '') ||
  (process.env.OOB_ALLOW_PHYSICAL_DEVICE === '1' && /^[A-Za-z0-9._:-]+$/.test(serial || ''))) && label,
  'Explicit device and label required; physical devices require OOB_ALLOW_PHYSICAL_DEVICE=1');
const adb = (...args) => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args],
  {encoding: 'utf8', timeout: 30000});
const path = '/data/local/tmp/oob-agent-control.xml';
assert.match(adb('shell', 'uiautomator', 'dump', path), /dumped to:/);
const nodes = [...adb('shell', 'cat', path).matchAll(/<node\b[^>]*>/g)];
const labelOf = node => (node.match(/content-desc="([^"]*)"/)?.[1] || '')
  .replaceAll('&#10;', '\n').replaceAll('&amp;', '&').split('\n')[0];
const boundsOf = node => [...node.match(/bounds="([^"]*)"/)[1].matchAll(/\d+/g)].map(([n]) => +n);
const parents = parentLabel ? nodes.filter(([node]) => labelOf(node) === parentLabel) : [];
assert(!parentLabel || parents.length === 1, 'Expected one visible parent row');
const parentBounds = parents.length ? boundsOf(parents[0][0]) : null;
const matches = nodes
  .filter(([node]) => node.includes('package="cn.com.omnimind.bot"') &&
    node.includes('clickable="true"') && node.includes('enabled="true"'))
  .filter(([node]) => labelOf(node) === label)
  .filter(([node]) => {
    if (!parentBounds) return true;
    const b = boundsOf(node);
    return b[0] >= parentBounds[0] && b[1] >= parentBounds[1] &&
      b[2] <= parentBounds[2] && b[3] <= parentBounds[3];
  });
assert.equal(matches.length, 1, 'Expected one enabled visible matching control');
const b = boundsOf(matches[0][0]);
assert(b.length === 4 && b[2] > b[0] && b[3] > b[1]);
adb('shell', 'input', 'tap', String(Math.round((b[0]+b[2])/2)), String(Math.round((b[1]+b[3])/2)));
console.log(JSON.stringify({serial, control: label, tapped: true}));
