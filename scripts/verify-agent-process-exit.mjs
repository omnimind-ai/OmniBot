// Verify the exact PIDs captured for a test request have exited.
// Run only after the owning request has terminated, not during initialization.
// Usage: ADB=/path/to/adb node scripts/verify-agent-process-exit.mjs emulator-N PID...
// No killing, restarts, process-name guessing, or environment inspection.
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
const [serial, ...pids] = process.argv.slice(2);
assert(/^emulator-\d+$/.test(serial || '') && pids.length > 0 &&
  pids.every(pid => /^[1-9]\d*$/.test(pid)), 'Explicit emulator and captured PIDs required');
for (const pid of pids) {
  const result = execFileSync(process.env.ADB || 'adb', ['-s', serial, 'shell',
    `if test -d /proc/${pid}; then echo present; else echo absent; fi`],
  {encoding: 'utf8', timeout: 15000}).trim();
  assert.equal(result, 'absent', `Captured process ${pid} remains present`);
}
console.log(JSON.stringify({serial, checkedPids: pids, exited: true}));
