#!/usr/bin/env node
// Real Wi-Fi outage, same app process and open session. Restores Wi-Fi even
// when the test fails. This is not a cellular-network requirement.
const {execFile} = require('node:child_process');
const {promisify} = require('node:util');
const path = require('node:path');
const assert = require('node:assert/strict');
const exec = promisify(execFile);
const [serial, sessionId, marker] = process.argv.slice(2);
assert(process.env.OOB_ALLOW_PHYSICAL_DEVICE === '1');
assert(serial && sessionId && /^OOB_[A-Z0-9_]+$/.test(marker || ''));
const adb = (...args) => exec('adb', ['-s',serial,...args], {timeout:15000}).then(r=>r.stdout.trim());
let changed = false;
async function restore() {
  if (changed) { await adb('shell','svc','wifi','enable'); changed = false; }
}
for (const signal of ['SIGINT','SIGTERM']) process.on(signal, () => {
  restore().finally(()=>process.exit(130));
});
(async () => {
  const pid = await adb('shell','pidof','cn.com.omnimind.bot');
  assert(/^\d+$/.test(pid), 'Phone app must already be open');
  assert.equal(await adb('shell','settings','get','global','wifi_on'), '1');
  assert(!(await adb('reverse','--list')).includes('tcp:17321'), 'Remove the test USB network forward first');
  try {
    changed = true;
    await adb('shell','svc','wifi','disable');
    let offline = false;
    for (let i=0;i<10;i++) {
      const state = await adb('shell','dumpsys','connectivity');
      if (/Active default network:\s*(?:none|null|\(none\)|-1)/i.test(state)) { offline = true; break; }
      await new Promise(r=>setTimeout(r,500));
    }
    assert(offline, 'Phone still has a default network; no outage acceptance');
    await exec(process.execPath,[path.join(__dirname,'verify-shared-external-send.cjs'),sessionId,marker],
      {timeout:80000,env:process.env});
    await restore();
    // Read-only observation of actual UI. Never reopen, refresh or replay.
    const deadline = Date.now()+60000;
    let visible = false;
    while (Date.now()<deadline) {
      await adb('shell','uiautomator','dump','/sdcard/oob-public-reconnect.xml');
      const xml = await adb('shell','cat','/sdcard/oob-public-reconnect.xml');
      if (xml.includes(`text="${marker}"`) || xml.includes(`content-desc="${marker}"`)) {
        visible = true; break;
      }
      await new Promise(r=>setTimeout(r,500));
    }
    assert(visible, 'No automatic visible catch-up within deadline');
    await exec(process.execPath,[path.join(__dirname,'verify-phone-shared-history.cjs'),serial,sessionId,marker],
      {timeout:70000,env:process.env});
    assert.equal(await adb('shell','pidof','cn.com.omnimind.bot'),pid);
    console.log(JSON.stringify({result:'PASS',serial,sessionId,marker,unchangedAppPid:pid,
      scope:'actual Wi-Fi outage and automatic catch-up on the configured public WSS route; native desktop UI unverified'}));
  } finally { await restore(); }
})().catch(error=>{console.error(error.message);process.exitCode=1});
