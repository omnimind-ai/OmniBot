// Kimi Web real-device acceptance after sending `Reply MARKER` through its UI.
// Usage: ADB=/path/to/adb node scripts/verify-kimi-web-reply.mjs SERIAL MARKER
// Also run after a page reload to check vendor-owned session persistence.
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const [serial, marker] = process.argv.slice(2);
assert(serial && /^[A-Z0-9_]+$/.test(marker || ''), 'Explicit device and test marker required');
const adb = (...args) => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args],
  {encoding: 'utf8', timeout: 30000});
const path = '/data/local/tmp/oob-kimi-web-reply.xml';
assert.match(adb('shell', 'uiautomator', 'dump', path), /dumped to:/);
const xml = adb('shell', 'cat', path);
const nodes = [...xml.matchAll(/<node\b[^>]*>/g)].map(([node]) => ({
  text: node.match(/text="([^"]*)"/)?.[1] || '',
  label: node.match(/content-desc="([^"]*)"/)?.[1] || '',
  enabled: node.includes('enabled="true"'),
  isText: node.includes('class="android.widget.TextView"'),
}));
assert(nodes.some(n => n.label === 'Switch session / workspace'), 'Kimi conversation not visible');
assert.equal(nodes.filter(n => n.isText && n.text === marker).length, 1,
  'Expected a visible assistant reply, not the user prompt or input draft');
assert(!nodes.some(n => n.label === 'Interrupt' && n.enabled), 'Response still running');
console.log(JSON.stringify({serial, marker, visibleReply: true, requestFinished: true}));
