// After a real test reply is visible, assert its footer through Android's UI.
// Usage: ADB=/path/to/adb node scripts/verify-reply-timing.mjs SERIAL REPLY_MARKER
// Read-only: does not send prompts, clear history, or fabricate timing.
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';

const [serial, marker] = process.argv.slice(2);
assert(serial && marker, 'Supply explicit device serial and unique reply marker');
const adb = (...args) => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args], {
  encoding: 'utf8', timeout: 30000,
});
const path = '/data/local/tmp/oob-reply-timing.xml';
assert.match(adb('shell', 'uiautomator', 'dump', path), /dumped to:/);
const xml = adb('shell', 'cat', path);
const decode = value => value.replaceAll('&#10;', '\n').replaceAll('&amp;', '&');
const replies = [...xml.matchAll(/<node\b[^>]*>/g)]
  .filter(([node]) => node.includes('class="android.widget.ImageView"'))
  .map(([node]) => decode(node.match(/content-desc="([^"]*)"/)?.[1] || ''))
  .filter(value => value.includes(marker));
assert.equal(replies.length, 1, 'Expected one visible assistant reply with marker');
assert.match(replies[0], /\b\d{2}:\d{2}:\d{2}\b/, 'Completion time missing');
assert.match(replies[0], /\b\d+(?:\.\d+)?(?:ms|s|m|h)\b/, 'Elapsed time missing');
console.log(JSON.stringify({ serial, completionTimeVisible: true, elapsedTimeVisible: true }));
