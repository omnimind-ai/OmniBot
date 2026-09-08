#!/usr/bin/env node
// Read-only device readiness gate. Does not install, clear data, send prompts,
// expose app storage, or claim Harness/model conversation acceptance.
// Usage: ADB=/path/to/adb node scripts/verify-agent-device.mjs SERIAL
import { execFileSync } from 'node:child_process';
import assert from 'node:assert/strict';

const serial = process.argv[2];
assert(serial && !serial.startsWith('-'), 'Pass an explicit device serial');
const adb = process.env.ADB || 'adb';
const run = (...args) => execFileSync(adb, ['-s', serial, ...args], {
  encoding: 'utf8', timeout: 15000, stdio: ['ignore', 'pipe', 'pipe'],
}).trim();

try {
  assert.equal(run('get-state'), 'device', 'Device is not online');
  assert.equal(run('shell', 'getprop', 'sys.boot_completed'), '1', 'Boot incomplete');
  const pkg = run('shell', 'dumpsys', 'package', 'cn.com.omnimind.bot');
  const version = pkg.match(/versionName=([^\s]+)/)?.[1];
  assert(version, 'App not installed');
  const pid = run('shell', 'pidof', 'cn.com.omnimind.bot');
  assert(/^\d+( \d+)*$/.test(pid), 'App process is not running');
  const windows = run('shell', 'dumpsys', 'window');
  assert(/mCurrentFocus=.*cn\.com\.omnimind\.bot\//.test(windows),
    'App is not foreground');
  console.log(JSON.stringify({
    gate: 'device-readiness', serial, version, passed: true,
    conversationAcceptance: 'not tested by this gate',
  }));
} catch (error) {
  // Do not forward arbitrary shell output (which may contain private content).
  console.error(JSON.stringify({gate: 'device-readiness', serial, passed: false,
    reason: error.code === 'ERR_ASSERTION' ? error.message : 'ADB command failed'}));
  process.exitCode = 1;
}
