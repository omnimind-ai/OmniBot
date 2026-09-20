#!/usr/bin/env node
// Real-phone fault fixture: drop exactly one session/prompt response, then
// close that transport. Never resend a prompt; inspect authoritative history.
const assert = require('node:assert/strict');
const http = require('node:http');
const {execFileSync, spawnSync} = require('node:child_process');
const {WebSocket, WebSocketServer} = require('ws');
const path = require('node:path');
const [serial, sessionId, marker] = process.argv.slice(2);
assert(process.env.OOB_ALLOW_PHYSICAL_DEVICE === '1');
assert(serial && sessionId && /^OOB_[A-Z0-9_]+$/.test(marker || ''));
const targetPort = Number(process.env.OOB_TEST_FORWARD_PORT);
const proxyPort = Number(process.env.OOB_TEST_FAULT_PORT);
const phonePort = Number(process.env.OOB_TEST_PHONE_PORT);
for (const port of [targetPort, proxyPort, phonePort]) assert(port >= 1024 && port <= 65535);
assert(proxyPort !== targetPort);
const adb = (...args) => execFileSync('adb', ['-s', serial, ...args], {encoding: 'utf8', timeout: 15000});
const originalPid = adb('shell', 'pidof', 'cn.com.omnimind.bot').trim();
assert(/^\d+$/.test(originalPid));
const peers = new Set();
let dropped = false;
let restored = false;
let droppedResolve;
const droppedPromise = new Promise(resolve => {droppedResolve = resolve;});
const server = http.createServer((req, res) => {
  const upstream = http.request({hostname: '127.0.0.1', port: targetPort,
    path: req.url, method: req.method, headers: req.headers}, incoming => {
    res.writeHead(incoming.statusCode, incoming.headers); incoming.pipe(res);
  });
  upstream.on('error', () => {res.writeHead(502); res.end();});
  req.pipe(upstream);
});
const wss = new WebSocketServer({server});
wss.on('connection', (phone, request) => {
  const upstream = new WebSocket(`ws://127.0.0.1:${targetPort}${request.url}`, {
    perMessageDeflate: false, headers: {Authorization: request.headers.authorization || ''}});
  peers.add(phone); peers.add(upstream);
  const early = [];
  const promptIds = new Set();
  phone.on('message', bytes => {
    try {
      const envelope = JSON.parse(bytes);
      if (envelope.type === 'stdin') {
        const rpc = JSON.parse(envelope.line);
        if (rpc.method === 'session/prompt') {
          assert.equal(rpc.params.sessionId, sessionId, 'Only isolated session may be faulted');
          promptIds.add(rpc.id);
        }
      }
    } catch (error) { if (error instanceof assert.AssertionError) throw error; }
    if (upstream.readyState === WebSocket.OPEN) upstream.send(bytes.toString());
    else early.push(bytes.toString());
  });
  upstream.on('open', () => {for (const bytes of early) upstream.send(bytes);});
  upstream.on('message', bytes => {
    let rpc;
    try {const e = JSON.parse(bytes); if (e.type === 'stdout') rpc = JSON.parse(e.line);} catch {}
    if (!dropped && rpc && promptIds.has(rpc.id) && rpc.result) {
      dropped = true;
      console.log('DROPPED real backend prompt ACK; closing only phone transport');
      phone.terminate(); upstream.terminate(); droppedResolve(); return;
    }
    if (phone.readyState === WebSocket.OPEN) phone.send(bytes.toString());
  });
  for (const peer of [phone, upstream]) {
    peer.on('error', () => {});
    peer.on('close', () => {peers.delete(peer);
      const other = peer === phone ? upstream : phone;
      if (other.readyState === WebSocket.OPEN) other.close();
    });
  }
  console.log('Phone fault-proxy connection established');
});
let backend;
async function run() {
  await new Promise(resolve => server.listen(proxyPort, '127.0.0.1', resolve));
  adb('reverse', `tcp:${phonePort}`, `tcp:${proxyPort}`);
  console.log('ARMED: reconnect the test phone transport, then send from phone: Reply only ' + marker);
  let timer;
  try {
    await Promise.race([droppedPromise, new Promise((_, reject) => {
      timer = setTimeout(() => reject(Error('No real phone prompt ACK observed')), 120000);
    })]);
  } finally {clearTimeout(timer);}
  backend = new WebSocket(process.env.OOB_SHARED_CODEX_URL, {perMessageDeflate: false});
  const pending = new Map(); let sequence = 0;
  backend.on('message', bytes => {
    const msg = JSON.parse(bytes), entry = pending.get(msg.id);
    if (entry) {pending.delete(msg.id); msg.error ? entry.reject(Error('Backend RPC failed')) : entry.resolve(msg.result);}
  });
  await new Promise((resolve, reject) => {backend.once('open', resolve); backend.once('error', reject);});
  const rpc = (method, params) => new Promise((resolve, reject) => {
    const id = ++sequence; pending.set(id, {resolve, reject}); backend.send(JSON.stringify({id, method, params}));
  });
  await rpc('initialize', {clientInfo: {name: 'oob-lost-ack-verifier', version: '1'}});
  const deadline = Date.now() + 60000;
  let completed = false;
  while (Date.now() < deadline) {
    const {thread} = await rpc('thread/read', {threadId: sessionId, includeTurns: true});
    assert(/\/oob-shared-session-probe$/.test(thread.cwd));
    const turns = thread.turns.filter(turn => turn.items.some(item => item.type === 'userMessage' && item.content.some(c => c.text?.includes(marker))));
    assert(turns.length <= 1, 'Phone prompt must execute at most once');
    // Admission can precede the authoritative user item becoming readable.
    // Wait for persistence; never retry the prompt to manufacture that item.
    if (turns[0]?.status === 'completed') {completed = true; break;}
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  assert(completed, 'Backend did not finish the admitted turn');
  const result = spawnSync(process.execPath, [path.join(__dirname, 'verify-phone-shared-history.cjs'), serial, sessionId, marker],
    {env: process.env, encoding: 'utf8', timeout: 70000});
  assert.equal(result.status, 0, result.stderr || result.stdout);
  assert.equal(adb('shell', 'pidof', 'cn.com.omnimind.bot').trim(), originalPid);
  console.log(JSON.stringify({result: 'PASS', serial, sessionId, marker, droppedPromptAck: true,
    unchangedAppPid: originalPid, acceptance: 'Actual phone automatically reconciles one admitted turn after lost ACK; no page reopen or prompt replay'}));
}
const watchdog = setTimeout(() => {console.error('Fault fixture deadline exceeded'); process.exit(1);}, 210000);
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => process.exit(1));
process.on('exit', () => {
  if (!restored) {try {adb('reverse', `tcp:${phonePort}`, `tcp:${targetPort}`);} catch {}}
});
run().catch(error => {console.error(error.message); process.exitCode = 1;}).finally(() => {
  clearTimeout(watchdog);
  adb('reverse', `tcp:${phonePort}`, `tcp:${targetPort}`); restored = true;
  backend?.terminate(); for (const peer of peers) peer.terminate(); wss.close(); server.close();
});
