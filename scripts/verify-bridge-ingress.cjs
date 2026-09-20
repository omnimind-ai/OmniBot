#!/usr/bin/env node
// Exercises authenticated ACP connections, not empty WebSocket capacity.
// No session/prompt, session/new, file writes or model generations.
// Only lists the explicitly configured test cwd; no file contents are read.
const WebSocket = require('ws');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const { performance } = require('node:perf_hooks');

function waitFor(ws, event, predicate, timeoutMs) {
  return new Promise((resolve, reject) => {
    const finish = (error, value) => {
      clearTimeout(timer);
      ws.off(event, onEvent); ws.off('error', onError); ws.off('close', onClose);
      error ? reject(error) : resolve(value);
    };
    const onEvent = (...args) => {
      try { if (predicate(...args)) finish(null, args[0]); }
      catch { finish(Error('Invalid Bridge response')); }
    };
    const onError = () => finish(Error('WebSocket transport failed'));
    const onClose = () => finish(Error('Bridge closed before expected response'));
    const timer = setTimeout(() => finish(Error(`Timed out waiting for ${event}`)), timeoutMs);
    ws.on(event, onEvent); ws.on('error', onError); ws.on('close', onClose);
  });
}
function envelope(raw) { return JSON.parse(raw.toString()); }
async function openBridge(url, token, cwd, sockets, timeoutMs) {
  const ws = new WebSocket(url, {perMessageDeflate: false,
    headers: {Authorization: `Bearer ${token}`}, handshakeTimeout: timeoutMs});
  sockets.push(ws);
  ws.on('error', () => {}); // Every active operation also observes errors.
  await waitFor(ws, 'open', () => true, timeoutMs);
  const hello = waitFor(ws, 'message', raw => envelope(raw).type === 'hello', timeoutMs);
  ws.send(JSON.stringify({type: 'hello', protocol: 'acp', token, cwd}));
  return {ws, hello: envelope(await hello)};
}
async function probeBridge({url, token, cwd, connections = 1, protocolVersion = 2,
  timeoutMs = 15000, pingCount = 3}) {
  const parsed = new URL(url);
  assert(['ws:', 'wss:'].includes(parsed.protocol));
  assert(!parsed.username && !parsed.password && !parsed.search, 'Credentials belong in a private token file');
  assert(token && cwd);
  assert(Number.isInteger(connections) && connections > 0 && connections <= 32);
  assert([1, 2].includes(protocolVersion));
  const sockets = [];
  try {
    // Match the Android client's separate HTTP readiness check. A working
    // WebSocket alone must not pass when the website drops /health.
    const healthUrl = new URL('/health', parsed);
    healthUrl.protocol = parsed.protocol === 'wss:' ? 'https:' : 'http:';
    const health = await fetch(healthUrl, {headers: {Authorization: `Bearer ${token}`},
      signal: AbortSignal.timeout(timeoutMs)});
    assert.equal(health.status, 200, 'Authenticated /health route unavailable');
    const readiness = await health.json();
    assert.equal(readiness.ready, true, 'Bridge reports transport not ready');
    const deniedHealth = await fetch(healthUrl, {headers: {Authorization: 'Bearer oob-invalid-ingress-probe'},
      signal: AbortSignal.timeout(timeoutMs)});
    assert([401, 403].includes(deniedHealth.status), 'Unauthenticated health request not rejected');
    const filesUrl = new URL('/fs/list', healthUrl);
    filesUrl.searchParams.set('path', cwd);
    const files = await fetch(filesUrl, {headers: {Authorization: `Bearer ${token}`},
      signal: AbortSignal.timeout(timeoutMs)});
    assert.equal(files.status, 200, 'Authenticated file listing route unavailable');
    assert.equal((await files.json()).ok, true, 'Bridge file listing failed');
    const deniedFiles = await fetch(filesUrl, {signal: AbortSignal.timeout(timeoutMs)});
    assert([401, 403].includes(deniedFiles.status), 'Unauthenticated file listing not rejected');
    const denied = await openBridge(url, 'oob-invalid-ingress-probe', cwd, sockets, timeoutMs);
    assert.equal(denied.hello.ok, false, 'Invalid credentials were accepted');
    denied.ws.close();
    const attempts = await Promise.allSettled(Array.from({length: connections}, async () => {
      const started = performance.now();
      const {ws, hello} = await openBridge(url, token, cwd, sockets, timeoutMs);
      assert.equal(hello.ok, true, 'Authenticated Bridge hello rejected');
      const helloMs = performance.now() - started;
      const initialized = waitFor(ws, 'message', raw => {
        const e = envelope(raw);
        return e.type === 'stdout' && JSON.parse(e.line).id === 1;
      }, timeoutMs);
      const params = protocolVersion === 2
        ? {protocolVersion: 2, info: {name: 'oob-ingress-probe', version: '1'}, capabilities: {}}
        : {protocolVersion: 1, clientInfo: {name: 'oob-ingress-probe', version: '1'}, clientCapabilities: {}};
      ws.send(JSON.stringify({type: 'stdin', line: JSON.stringify({jsonrpc: '2.0', id: 1, method: 'initialize', params})}));
      const init = JSON.parse(envelope(await initialized).line);
      assert(!init.error, 'ACP initialize returned an error');
      assert.equal(init.result?.protocolVersion, protocolVersion, 'ACP version mismatch');
      const initializedMs = performance.now() - started;
      const pingMs = [];
      for (let i = 0; i < pingCount; i++) {
        const payload = Buffer.from(`oob-ingress-${i}`);
        const pong = waitFor(ws, 'pong', data => data.equals(payload), timeoutMs);
        const sent = performance.now(); ws.ping(payload); await pong;
        pingMs.push(performance.now() - sent);
      }
      return {helloMs, initializedMs, pingMs};
    }));
    const failures = attempts.filter(x => x.status === 'rejected');
    assert.equal(failures.length, 0, `${failures.length}/${connections} authenticated ACP connections failed`);
    assert(sockets.slice(1).every(ws => ws.readyState === WebSocket.OPEN), 'Connection dropped during concurrent test');
    const samples = attempts.map(x => x.value);
    const pings = samples.flatMap(s => s.pingMs).sort((a, b) => a - b);
    const round = n => Math.round(n * 100) / 100;
    return {result: 'PASS', encrypted: parsed.protocol === 'wss:', authenticatedConnections: connections,
      protocolVersion, healthReady: true, fileListingReady: true, unauthorizedHelloRejected: true,
      maxInitializeMs: round(Math.max(...samples.map(s => s.initializedMs))),
      medianPingMs: round(pings[Math.floor(pings.length / 2)]), maxPingMs: round(pings.at(-1)),
      scope: 'Authenticated ACP initialization and WebSocket RTT only; not maximum capacity, model throughput, session recovery or phone/WAN acceptance'};
  } finally {
    // No prompt was admitted. Closing these clients cannot cancel a model turn.
    for (const ws of sockets) ws.terminate();
  }
}
module.exports = {probeBridge};
if (require.main === module) {
  (async () => {
    const result = await probeBridge({url: process.env.OOB_BRIDGE_URL,
      token: readFileSync(process.env.OOB_BRIDGE_TOKEN_FILE, 'utf8').trim(),
      cwd: process.env.OOB_BRIDGE_CWD,
      connections: Number(process.env.OOB_TEST_CONNECTIONS || 1),
      protocolVersion: Number(process.env.OOB_TEST_ACP_VERSION || 2)});
    console.log(JSON.stringify(result));
  })().catch(error => {console.error(error.message); process.exitCode = 1;});
}
