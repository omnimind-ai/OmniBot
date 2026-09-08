// Transfer one existing local test page to the separately installed Chromium.
// Keep the official ephemeral login URL in memory; never print/store it.
// Usage: ADB=/path/to/adb node scripts/open-agent-web-test-browser.mjs emulator-N CDP_PORT PAGE_PORT
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const [serial, cdpPort, pagePort] = process.argv.slice(2);
assert(/^emulator-\d+$/.test(serial || '') && /^\d+$/.test(cdpPort || '') &&
  /^\d+$/.test(pagePort || ''), 'Explicit emulator and observed ports required');
const tabs = await (await fetch(`http://127.0.0.1:${cdpPort}/json/list`)).json();
const matches = tabs.filter(tab => {
  if (tab.type !== 'page') return false;
  try {
    const url = new URL(tab.url);
    return url.protocol === 'http:' && url.hostname === '127.0.0.1' && url.port === pagePort;
  } catch { return false; }
});
assert.equal(matches.length, 1, 'Expected one existing local test page');
const loginUrl = new URL(matches[0].url);
assert(loginUrl.searchParams.has('token') || loginUrl.hash.startsWith('#token='),
  'Source page has already exchanged its login token; reopen the official login URL first');
try {
  execFileSync(process.env.ADB || 'adb', ['-s', serial, 'shell', 'am', 'start',
    '-a', 'android.intent.action.VIEW', '-p', 'org.chromium.chrome',
    '-d', `'${matches[0].url.replaceAll("'", "'\\''")}'`],
  {encoding: 'utf8', timeout: 30000, stdio: ['ignore', 'pipe', 'pipe']});
} catch {
  throw new Error('Could not open the existing local page in test Chromium');
}
console.log(JSON.stringify({serial, browser: 'org.chromium.chrome', launchDispatched: true}));
