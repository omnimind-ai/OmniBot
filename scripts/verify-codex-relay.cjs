#!/usr/bin/env node
// Read-only authentication and sequential RPC latency probe. Never sends prompts.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const WebSocket = require('ws');
const token = fs.readFileSync(process.env.OOB_SHARED_CODEX_TOKEN_FILE, 'utf8').trim();
const urls = process.argv.slice(2);
assert(urls.length, 'Pass explicit test WebSocket URLs');
async function connect(url, credential) {
  const socket = new WebSocket(url, credential ? { headers: { Authorization: `Bearer ${credential}` } } : {});
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { socket.terminate(); reject(Error('Connection timeout')); }, 10000);
    socket.once('open', () => { clearTimeout(timer); resolve(socket); });
    socket.on('error', error => { clearTimeout(timer); reject(error); });
  });
}
async function probe(url) {
  for (const credential of [null, 'invalid-test-token']) {
    let rejected = false;
    try { const socket = await connect(url, credential); socket.close(); }
    catch (error) { assert.match(error.message, /401/); rejected = true; }
    assert(rejected, 'Unauthenticated access must be rejected');
  }
  const socket = await connect(url, token);
  let id = 0;
  async function rpc(method, params) {
    const requestId = ++id;
    return new Promise((resolve, reject) => {
      const cleanup = () => { clearTimeout(timer); socket.off('message', receive); };
      const receive = bytes => {
        const message = JSON.parse(bytes);
        if (message.id !== requestId) return;
        cleanup(); message.error ? reject(Error(JSON.stringify(message.error))) : resolve(message.result);
      };
      const timer = setTimeout(() => { cleanup(); reject(Error('RPC timeout')); }, 10000);
      socket.on('message', receive);
      socket.send(JSON.stringify({ id: requestId, method, params }));
    });
  }
  try {
    await rpc('initialize', { clientInfo: { name: 'oob-relay-probe', version: '0.1.0' } });
    socket.send(JSON.stringify({ method: 'initialized', params: {} }));
    const samples = [];
    for (let i = 0; i < 20; i++) {
      const start = performance.now();
      await rpc('thread/loaded/list', {});
      samples.push(performance.now() - start);
    }
    samples.sort((a, b) => a - b);
    console.log(JSON.stringify({ url, authentication: 'PASS: missing/wrong token rejected; valid accepted', samples: samples.length, medianMs: +samples[10].toFixed(2), p95Ms: +samples[18].toFixed(2) }));
  } finally { socket.close(); }
}
(async () => { for (const url of urls) await probe(url); })().catch(error => { console.error(error.message); process.exitCode = 1; });
