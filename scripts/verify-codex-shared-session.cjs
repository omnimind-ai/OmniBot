#!/usr/bin/env node
// Actual app-server integration probe. Creates one isolated test thread and
// sends a seed prompt and one phone prompt; reconnect never resends a prompt.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { once } = require('node:events');
const { spawn } = require('node:child_process');
const { Duplex } = require('node:stream');
const WebSocket = require('ws');
const url = process.env.OOB_SHARED_CODEX_URL;
if (!url) throw Error('Set OOB_SHARED_CODEX_URL to the explicitly selected test app-server');
const marker = `OOB_SHARED_${Date.now()}`;
const clients = [];
async function connect(name) {
  const tokenPath = process.env.OOB_SHARED_CODEX_TOKEN_FILE;
  const token = tokenPath ? fs.readFileSync(tokenPath, 'utf8').trim() : null;
  let proxy;
  let socket;
  if (name === 'oob-desktop-probe' && process.env.OOB_TEST_SSH_TARGET) {
    const target = process.env.OOB_TEST_SSH_TARGET;
    assert(!target.startsWith('-'), 'SSH target cannot be an option');
    const args = ['-T', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes'];
    if (process.env.OOB_TEST_SSH_CONFIG) args.push('-F', process.env.OOB_TEST_SSH_CONFIG);
    args.push(target, 'exec codex app-server proxy');
    proxy = spawn('ssh', args, { stdio: ['pipe', 'pipe', 'ignore'] });
    const stream = Duplex.from({ readable: proxy.stdout, writable: proxy.stdin });
    socket = new WebSocket('ws://codex-app-server/rpc', { perMessageDeflate: false, handshakeTimeout: 10000, createConnection: () => stream });
    proxy.on('error', error => stream.destroy(error));
    socket.on('close', () => proxy.kill());
  } else {
    socket = new WebSocket(url, { perMessageDeflate: false, ...(token ? { headers: { Authorization: `Bearer ${token}` } } : {}) });
  }
  const pending = new Map(), events = [];
  let seq = 0;
  const client = { socket, events, async rpc(method, params) {
    const id = ++seq;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { pending.delete(id); reject(Error(`Timeout: ${method}`)); }, 45000);
      pending.set(id, { resolve, reject, timer });
      socket.send(JSON.stringify({ id, method, params }));
    });
  }};
  clients.push(client);
  socket.on('message', bytes => {
    const message = JSON.parse(bytes.toString());
    if (message.id != null && pending.has(message.id)) {
      const waiter = pending.get(message.id); pending.delete(message.id); clearTimeout(waiter.timer);
      message.error ? waiter.reject(Error(JSON.stringify(message.error))) : waiter.resolve(message.result);
    } else if (message.method) {
      events.push(message);
      // This probe authorizes no tools. Surface any unexpected server request.
      if (message.id != null) socket.send(JSON.stringify({ id: message.id, error: { code: -32601, message: 'Probe does not execute tools or approve requests' } }));
    }
  });
  socket.on('close', () => {
    for (const waiter of pending.values()) { clearTimeout(waiter.timer); waiter.reject(Error('Connection closed')); }
    pending.clear();
  });
  await once(socket, 'open');
  await client.rpc('initialize', { clientInfo: { name, version: '0.1.0' }, capabilities: { experimentalApi: true } });
  socket.send(JSON.stringify({ method: 'initialized', params: {} }));
  return client;
}
async function until(check) {
  const end = Date.now() + 120000;
  while (Date.now() < end) { if (check()) return; await new Promise(r => setTimeout(r, 100)); }
  throw Error('Timed out awaiting the shared turn completion');
}
(async () => {
  const desktop = await connect('oob-desktop-probe');
  const phone = await connect('oob-phone-probe');
  const cwd = '/tmp/oob-shared-session-probe'; fs.mkdirSync(cwd, { recursive: true });
  const started = await desktop.rpc('thread/start', { cwd, sandbox: 'read-only', approvalPolicy: 'never' });
  const threadId = started.thread.id;
  // A brand-new empty thread has no persisted rollout to resume yet.
  const seed = await desktop.rpc('turn/start', { threadId, input: [{ type: 'text', text: 'Do not call tools. Reply with READY.', text_elements: [] }] });
  await until(() => desktop.events.some(e => e.method === 'turn/completed' && e.params?.turn?.id === seed.turn.id));
  const loaded = await phone.rpc('thread/resume', { threadId });
  assert.equal(loaded.thread.id, threadId);
  console.log('PASS both clients bind the same thread');
  const turn = await phone.rpc('turn/start', { threadId, input: [{ type: 'text', text: `Do not call tools. Reply with exactly ${marker}.`, text_elements: [] }] });
  const turnId = turn.turn.id;
  const completed = client => client.events.find(e => e.method === 'turn/completed' && e.params?.turn?.id === turnId);
  await until(() => completed(desktop) && completed(phone));
  assert.equal(completed(desktop).params.turn.status, 'completed');
  assert.equal(completed(phone).params.turn.status, 'completed');
  const sharedOutput = client => client.events.some(e => e.method === 'item/agentMessage/delta' && e.params?.turnId === turnId);
  assert(sharedOutput(desktop)); assert(sharedOutput(phone));
  const desktopView = await desktop.rpc('thread/read', { threadId, includeTurns: true });
  assert(JSON.stringify(desktopView.thread.turns).includes(marker));
  console.log('PASS phone prompt produces output and completion on both protocol clients');
  phone.socket.close(); await once(phone.socket, 'close');
  const reconnected = await connect('oob-phone-reconnected-probe');
  const restored = await reconnected.rpc('thread/resume', { threadId });
  const history = await reconnected.rpc('thread/read', { threadId, includeTurns: true });
  assert.equal(restored.thread.id, threadId);
  assert.equal(history.thread.turns.filter(t => t.id === turnId).length, 1);
  assert.equal(history.thread.turns.length, desktopView.thread.turns.length);
  assert(JSON.stringify(history.thread.turns).includes(marker));
  console.log('PASS reconnect restores history without sending the prompt again');
  const interruptedMarker = `${marker}_DISCONNECT`;
  const inFlight = await reconnected.rpc('turn/start', { threadId, input: [{ type: 'text', text: `Do not call tools. Reply with exactly ${interruptedMarker}.`, text_elements: [] }] });
  reconnected.socket.close(); await once(reconnected.socket, 'close');
  await until(() => desktop.events.some(e => e.method === 'turn/completed' && e.params?.turn?.id === inFlight.turn.id));
  const recovered = await connect('oob-phone-after-disconnect-probe');
  await recovered.rpc('thread/resume', { threadId });
  const recoveredHistory = await recovered.rpc('thread/read', { threadId, includeTurns: true });
  assert.equal(recoveredHistory.thread.turns.filter(t => t.id === inFlight.turn.id).length, 1);
  assert.equal(recoveredHistory.thread.turns.length, history.thread.turns.length + 1);
  const completedAfterDisconnect = recoveredHistory.thread.turns.find(t => t.id === inFlight.turn.id);
  assert.equal(completedAfterDisconnect.status, 'completed', 'Disconnect must not interrupt generation');
  assert.equal(completedAfterDisconnect.items.filter(i => i.type === 'agentMessage' && i.text === interruptedMarker).length, 1, 'Exactly one complete assistant reply after disconnect');
  desktop.socket.close(); await once(desktop.socket, 'close');
  const desktopAgain = await connect('oob-desktop-reconnected-probe');
  await desktopAgain.rpc('thread/resume', { threadId });
  const desktopHistory = await desktopAgain.rpc('thread/read', { threadId, includeTurns: true });
  assert.deepEqual(desktopHistory.thread.turns.map(t => t.id), recoveredHistory.thread.turns.map(t => t.id));
  console.log('PASS disconnect during generation preserves execution; both clients recover the same turn ids');
  console.log(JSON.stringify({ threadId, turnId, marker, acceptance: 'protocol-only on supplied endpoint; native desktop UI and physical phone not verified' }));
})().catch(error => { console.error(error.message); process.exitCode = 1; }).finally(() => { for (const c of clients) c.socket.terminate(); });
