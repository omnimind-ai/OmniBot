// Read-only check after opening the chat composer keyboard on the test device.
// Usage: ADB=/path/to/adb node scripts/verify-chat-keyboard.mjs emulator-N
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const serial = process.argv[2];
assert(/^emulator-\d+$/.test(serial || ''), 'Explicit emulator required');
const adb = (...args) => execFileSync(process.env.ADB || 'adb', ['-s', serial, ...args],
  {encoding: 'utf8', timeout: 30000});
assert(/mInputShown=true/.test(adb('shell', 'dumpsys', 'input_method')),
  'Open the composer keyboard first');
const path = '/data/local/tmp/oob-chat-keyboard.xml';
assert.match(adb('shell', 'uiautomator', 'dump', path), /dumped to:/);
const xml = adb('shell', 'cat', path);
const inputs = [...xml.matchAll(/<node\b[^>]*>/g)].filter(([node]) =>
  node.includes('package="cn.com.omnimind.bot"') &&
  node.includes('class="android.widget.EditText"') && node.includes('focused="true"'));
assert.equal(inputs.length, 1, 'Focused chat input must remain visible above keyboard');
assert(xml.includes('content-desc="Model &amp; settings"'),
  'Chat composer actions must remain visible with keyboard open');
console.log(JSON.stringify({serial, keyboardShown: true, focusedComposerVisible: true}));
