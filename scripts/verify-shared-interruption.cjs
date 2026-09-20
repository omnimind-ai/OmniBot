#!/usr/bin/env node
// Wait for a real phone-originated test turn, interrupt only its isolated
// Bridge, and verify the shared backend finishes that same turn once.
const { execFileSync } = require('node:child_process');
const assert = require('node:assert/strict');
const WebSocket = require('ws');
const fs = require('node:fs');
const path = require('node:path');
const [sessionId, marker, bridgePidText] = process.argv.slice(2);
assert(sessionId && /^OOB_[A-Z0-9_]+$/.test(marker));
const bridgePid = Number(bridgePidText);
assert(Number.isInteger(bridgePid) && bridgePid > 1);
const command = execFileSync('ps', ['-p', String(bridgePid), '-o', 'args='], { encoding: 'utf8' });
const bridgePort = Number(process.env.OOB_TEST_BRIDGE_PORT || 17321);
assert([17321, 17334, 17336, 17339].includes(bridgePort), 'Only isolated acceptance ports');
const ownedLoopback = bridgePort === 17339 &&
  command.includes(path.resolve(__dirname, '../tools/codex-bridge/server.mjs')) &&
  command.includes('--host 127.0.0.1 ') && command.includes('--port 17339 ');
assert(ownedLoopback || (command.includes('/tmp/oob-phone-bridge-runtime/shared-server.mjs') &&
  command.includes(`--port ${bridgePort} `)), 'Only named isolated test Bridge may be interrupted');
const unixSocket = process.env.OOB_SHARED_CODEX_SOCKET;
assert(!!unixSocket !== !!process.env.OOB_SHARED_CODEX_URL, 'Select one backend transport');
if (unixSocket) {
  const stat = fs.lstatSync(unixSocket);
  assert(path.isAbsolute(unixSocket) && !unixSocket.includes(':') && stat.isSocket() &&
    stat.uid === process.getuid() && !(stat.mode & 0o077), 'Private owned Unix socket required');
}
const socket = new WebSocket(unixSocket ? `ws+unix://${unixSocket}:/` : process.env.OOB_SHARED_CODEX_URL,
  { perMessageDeflate: false });
const pending = new Map(); let seq = 0;
function rpc(method, params) {
  return new Promise((resolve, reject) => {
    const id = ++seq;
    const timer = setTimeout(() => { pending.delete(id); reject(Error('RPC timeout')); }, 10000);
    pending.set(id, { resolve, reject, timer });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
socket.on('message', bytes => {
  const msg = JSON.parse(bytes); const waiter = pending.get(msg.id);
  if (!waiter) return;
  clearTimeout(waiter.timer); pending.delete(msg.id);
  msg.error ? waiter.reject(Error(JSON.stringify(msg.error))) : waiter.resolve(msg.result);
});
socket.on('error', error => { console.error(error.message); process.exitCode = 1; });
socket.on('open', async () => {
  try {
    await rpc('initialize', { clientInfo: { name: 'oob-interruption-verifier', version: '1' } });
    const read = async () => (await rpc('thread/read', { threadId: sessionId, includeTurns: true })).thread;
    const baseline = await read();
    if (process.env.OOB_TEST_CWD) {
      assert.equal(fs.realpathSync(baseline.cwd), fs.realpathSync(process.env.OOB_TEST_CWD));
    } else assert(/\/oob-shared-session-probe$/.test(baseline.cwd));
    assert(!JSON.stringify(baseline.turns).includes(marker), 'Marker must be a fresh test input');
    if (process.env.OOB_TEST_SUBSCRIBE === '1') {
      await rpc('thread/resume', { threadId: sessionId });
      console.log('Protocol observer subscribed; this does not verify native desktop UI');
    }
    console.log('ARMED: send fresh marker from phone:', marker);
    let interruptedTurnId;
    const deadline = Date.now() + 240000;
    while (Date.now() < deadline) {
      const thread = await read();
      const turns = thread.turns.filter(turn => turn.items.some(item => item.type === 'userMessage' && item.content.some(c => c.text?.includes(marker))));
      assert(turns.length <= 1, 'Duplicate logical execution');
      const turn = turns[0];
      if (turn && !interruptedTurnId) {
        assert.equal(turn.status, 'inProgress', 'Must interrupt during generation, not after completion');
        if (process.env.OOB_TEST_PHONE_SERIAL) {
          execFileSync('adb', ['-s', process.env.OOB_TEST_PHONE_SERIAL, 'shell', 'am', 'force-stop', 'cn.com.omnimind.bot']);
        } else process.kill(bridgePid, 'SIGTERM');
        interruptedTurnId = turn.id;
        console.log(process.env.OOB_TEST_PHONE_SERIAL ? 'Stopped phone app during actual phone turn:' : 'Interrupted isolated Bridge during actual phone turn:', turn.id);
      }
      if (interruptedTurnId && ['interrupted', 'failed'].includes(turn?.status)) {
        throw Error(`Interrupted phone turn did not continue: ${turn.id} status=${turn.status}`);
      }
      if (interruptedTurnId && turn?.status === 'completed') {
        assert.equal(turn.id, interruptedTurnId);
        assert.equal(thread.turns.length, baseline.turns.length + 1);
        assert(turn.items.some(item => item.type === 'agentMessage' && item.text === marker));
        console.log(JSON.stringify({ result: 'PASS', sessionId, turnId: turn.id, marker, turns: thread.turns.length, interruption: process.env.OOB_TEST_PHONE_SERIAL ? 'phone-app-force-stop' : 'bridge-process-stop', acceptance: 'real phone-originated turn completes exactly once; phone recovery checked separately' }));
        return;
      }
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    throw Error('Timed out awaiting real phone turn and completion');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
  finally { socket.close(); }
});
