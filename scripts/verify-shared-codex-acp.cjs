#!/usr/bin/env node
// Real upstream ACP adapter acceptance against an explicitly chosen test thread.
const { spawn } = require('node:child_process');
const readline = require('node:readline');
const assert = require('node:assert/strict');
const child = spawn(process.env.OOB_ACP_BIN, [], { env: process.env, stdio: ['pipe', 'pipe', 'ignore'] });
const pending = new Map();
let id = 0, updates = 0;
readline.createInterface({ input: child.stdout }).on('line', line => {
  const msg = JSON.parse(line);
  if (msg.method === 'session/update') updates++;
  const waiter = pending.get(msg.id);
  if (!waiter) return;
  pending.delete(msg.id); clearTimeout(waiter.timer);
  msg.error ? waiter.reject(Error(JSON.stringify(msg.error))) : waiter.resolve(msg.result);
});
function rpc(method, params) {
  const requestId = ++id;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(requestId); reject(Error('Timeout: ' + method)); }, 45000);
    pending.set(requestId, { resolve, reject, timer });
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: requestId, method, params }) + '\n');
  });
}
(async () => {
  try {
    const initialized = await rpc('initialize', { protocolVersion: 1, clientInfo: { name: 'oob-shared-acp-probe', version: '1' }, clientCapabilities: {} });
    assert.equal(initialized.protocolVersion, 1);
    console.log('PASS upstream ACP initialize through shared transport');
    const params = { sessionId: process.env.OOB_TEST_SESSION_ID, cwd: '/tmp/oob-shared-session-probe', mcpServers: [], _meta: { 'dev.omnimind.codex/includeThreadSnapshot': true } };
    const loaded = await rpc('session/load', params);
    assert(updates > 0, 'History updates expected');
    console.log('PASS existing shared session loaded through upstream ACP; history updates:', updates);
    if (process.env.OOB_EXPECT_THREAD_SNAPSHOT === '1') {
      const snapshot = loaded._meta?.['dev.omnimind.codex/threadSnapshot'];
      assert.equal(snapshot?.id, params.sessionId);
      assert(snapshot.turns.length > 0);
      const again = await rpc('session/load', params);
      assert.deepEqual(again._meta?.['dev.omnimind.codex/threadSnapshot'].turns, snapshot.turns);
      console.log('PASS opt-in official thread snapshot retains turn/item identities; repeated load unchanged');
    }
  } finally { child.stdin.end(); child.kill(); }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
