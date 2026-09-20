#!/usr/bin/env node
// Actual phone remains on its already-open test session throughout the outage.
// Fault only an explicitly named test SSH forward; always restore its launchd job.
const {execFileSync, spawnSync} = require('node:child_process');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const [serial, sessionId, marker] = process.argv.slice(2);
const label = process.env.OOB_RECONNECT_FORWARD_LABEL;
assert(process.env.OOB_ALLOW_PHYSICAL_DEVICE === '1');
assert(serial && sessionId && /^OOB_[A-Z0-9_]+$/.test(marker || ''));
assert(/^cn\.omnimind\.codex-[a-z0-9.-]+-test\.forward$/.test(label || ''));
const domain = `gui/${process.getuid()}`;
const plist = path.join(os.homedir(), 'Library/LaunchAgents', label + '.plist');
assert(fs.existsSync(plist));
const run = (bin, args) => execFileSync(bin, args, {encoding: 'utf8', timeout: 90000});
const phonePid = () => run('adb', ['-s', serial, 'shell', 'pidof', 'cn.com.omnimind.bot']).trim();
const pid = phonePid();
assert(/^\d+$/.test(pid), 'Phone app must already be open');
run('launchctl', ['print', `${domain}/${label}`]);
let removed = false;
let restored = false;
try {
  run('launchctl', ['bootout', `${domain}/${label}`]);
  removed = true;
  const send = spawnSync(process.execPath, [path.join(__dirname, 'verify-shared-external-send.cjs'), sessionId, marker],
    {encoding: 'utf8', timeout: 80000, env: process.env});
  assert.equal(send.status, 0, send.stderr || send.stdout);
  run('launchctl', ['bootstrap', domain, plist]);
  restored = true;
  const verify = spawnSync(process.execPath, [path.join(__dirname, 'verify-phone-shared-history.cjs'), serial, sessionId, marker],
    {encoding: 'utf8', timeout: 70000, env: process.env});
  assert.equal(verify.status, 0, verify.stderr || verify.stdout);
  assert.equal(phonePid(), pid, 'App must not restart to recover');
  console.log(JSON.stringify({passed: true, serial, sessionId, marker, unchangedAppPid: pid,
    fault: 'test SSH forward removed during external send, then restored',
    acceptance: 'actual phone automatic history catch-up without page reopen; native desktop GUI and public WSS remain separate gates'}));
} finally {
  if (removed && !restored) run('launchctl', ['bootstrap', domain, plist]);
}
