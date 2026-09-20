#!/usr/bin/env node
// Real configured ACP agents, same supplied disposable session. Sends ONE prompt.
// Tests passive live observation independently from history restoration.
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const { createInterface } = require('node:readline');
const { randomUUID } = require('node:crypto');
const fs = require('node:fs');
const bin = process.env.OOB_ACP_BIN;
const sessionId = process.env.OOB_TEST_SESSION_ID;
const cwd = process.env.OOB_TEST_CWD;
const protocolVersion = Number(process.env.OOB_TEST_ACP_VERSION || 1);
assert([1, 2].includes(protocolVersion));
assert(bin && sessionId && cwd, 'Set OOB_ACP_BIN, OOB_TEST_SESSION_ID and OOB_TEST_CWD to a disposable test session');
const marker = `OOB_OBSERVER_${randomUUID().replaceAll('-', '').toUpperCase()}`;
const peers = [];
const results = { sessionId, marker, protocol: protocolVersion, acceptance: 'real ACP adapters only; native desktop UI, physical phone and WAN are separate gates' };
function peer(name) {
  const child = spawn(bin, [], { env: process.env, stdio: ['pipe', 'pipe', 'ignore'] });
  const pending = new Map(), events = [];
  let seq = 0;
  function fail(error) {
    for (const p of pending.values()) { clearTimeout(p.timer); p.reject(error); }
    pending.clear();
  }
  child.on('error', fail);
  child.on('exit', () => fail(Error(`${name} exited`)));
  createInterface({ input: child.stdout }).on('line', line => {
    let m;
    try { m = JSON.parse(line); } catch { fail(Error('Malformed ACP output')); return; }
    if (m.method === 'session/update') events.push(m.params);
    const p = pending.get(m.id);
    if (p && !m.method) {
      pending.delete(m.id); clearTimeout(p.timer);
      m.error ? p.reject(Error(JSON.stringify(m.error))) : p.resolve(m.result);
    } else if (m.method && m.id != null) {
      // No tool execution or approval is authorized by this probe.
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'Observation test provides no tools or approvals' } }) + '\n');
    }
  });
  const client = { child, events, rpc(method, params) {
    return new Promise((resolve, reject) => {
      const id = ++seq;
      const timer = setTimeout(() => { pending.delete(id); reject(Error(`${name}: ${method} timed out; do not resend prompt`)); }, 60000);
      pending.set(id, { resolve, reject, timer });
      child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
    });
  }, stop() { fail(Error('Test finished')); child.stdin.end(); child.kill(); } };
  peers.push(client);
  return client;
}
const loadParams = { sessionId, cwd, mcpServers: [], _meta: { 'dev.omnimind.codex/includeThreadSnapshot': true } };
if (protocolVersion === 2) loadParams.replayFrom = {type: 'start'};
const loadMethod = protocolVersion === 2 ? 'session/resume' : 'session/load';
function replyText(events) {
  if (protocolVersion === 2) {
    const messages = new Map();
    for (const {sessionId: id, update: u} of events) {
      if (id !== sessionId) continue;
      if (u.sessionUpdate === 'agent_message' && 'content' in u)
        messages.set(u.messageId, (u.content || []).map(c => c.text || '').join(''));
      if (u.sessionUpdate === 'agent_message_chunk')
        messages.set(u.messageId, (messages.get(u.messageId) || '') + (u.content?.text || ''));
    }
    return [...messages.values()].join('').trim();
  }
  return events.filter(e => e.sessionId === sessionId && e.update?.sessionUpdate === 'agent_message_chunk')
    .map(e => e.update.content?.text || '').join('').trim();
}
async function awaitCompletion(p) {
  if (protocolVersion !== 2) return;
  const deadline = Date.now() + 60000;
  while (!p.events.some(e => e.update?.sessionUpdate === 'state_update' &&
      e.update.state === 'idle' && e.update.stopReason)) {
    assert(Date.now() < deadline, 'No official v2 terminal state; do not resend');
    await new Promise(r => setTimeout(r, 100));
  }
}
(async () => {
  try {
    const observer = peer('observer'), sender = peer('sender');
    for (const p of peers) {
      const info = {name: 'oob-observation-regression', version: '1'};
      const init = await p.rpc('initialize', { protocolVersion, ...(protocolVersion === 2 ?
        {info, capabilities: {}} : {clientInfo: info, clientCapabilities: {}}) });
      assert.equal(init.protocolVersion, protocolVersion, 'Protocol negotiation must match tested lifecycle');
      const loaded = await p.rpc(loadMethod, loadParams);
      const snapshot = loaded._meta?.['dev.omnimind.codex/threadSnapshot'];
      assert.equal(snapshot?.id, sessionId, 'Identity snapshot required to guard test target');
      assert.equal(fs.realpathSync(snapshot.cwd), fs.realpathSync(cwd), 'Configured workspace must match test session');
      assert(!JSON.stringify(snapshot.turns).includes(marker));
      p.events.length = 0;
    }
    const response = await sender.rpc('session/prompt', { sessionId, prompt: [{ type: 'text', text: `Reply only ${marker}. Do not use tools.` }] });
    if (protocolVersion === 2) {
      results.promptAcknowledgedWithoutStopReason = !('stopReason' in response);
      await awaitCompletion(sender);
    }
    results.promptStopReason = response.stopReason;
    results.senderReply = replyText(sender.events) === marker;
    results.senderReplyChars = replyText(sender.events).length;
    if (process.env.OOB_OBSERVATION_TRACE_FILE) fs.writeFileSync(
      process.env.OOB_OBSERVATION_TRACE_FILE,
      JSON.stringify({sessionId, marker, sender: sender.events, observer: observer.events}, null, 2),
      {mode: 0o600});
    // Bounded observation window after the official terminal response; no resend.
    const deadline = Date.now() + 5000;
    while (Date.now() < deadline && replyText(observer.events) !== marker) await new Promise(r => setTimeout(r, 100));
    results.passiveLiveReply = replyText(observer.events) === marker;
    results.observerLiveUpdateCount = observer.events.length;
    if (protocolVersion === 2) {
      await awaitCompletion(observer);
      results.passiveUserMessage = observer.events.some(e =>
        e.update?.sessionUpdate === 'user_message' && e.update.messageId &&
        e.update.content?.some(c => c.text?.includes(marker)));
    }
    const restored = await observer.rpc(loadMethod, loadParams);
    const snapshot = restored._meta?.['dev.omnimind.codex/threadSnapshot'];
    const turns = snapshot.turns.filter(t => t.items.some(i => i.type === 'userMessage' && i.content?.some(c => c.text?.includes(marker))));
    results.executionCount = turns.length;
    results.restoredExactlyOnce = turns.length === 1 && turns[0].status === 'completed' && turns[0].items.filter(i => i.type === 'agentMessage' && i.text === marker).length === 1;
    if (process.env.OOB_TEST_SHARED_IDENTITIES === '1') {
      const chunks = p => p.events.filter(e => e.update?.sessionUpdate === 'agent_message_chunk');
      const sent = chunks(sender);
      results.backendIdentityPreserved = sent.length > 0 && sent.every(e =>
        e.update._meta?.codex?.turnId === turns[0]?.id &&
        turns[0]?.items.some(i => i.id === e.update.messageId));
      // Reversing roles exercises the transition from a completed own prompt
      // back to passive observation. Each prompt has a fresh marker, no retry.
      const reverseMarker = marker + '_REVERSE';
      sender.events.length = 0;
      observer.events.length = 0;
      await observer.rpc('session/prompt', { sessionId, prompt: [{type: 'text', text:
        `Reply with exactly this text, without punctuation or tools:\n${reverseMarker}`}] });
      await awaitCompletion(observer);
      const reverseDeadline = Date.now() + 5000;
      while (Date.now() < reverseDeadline && replyText(sender.events) !== reverseMarker)
        await new Promise(r => setTimeout(r, 100));
      results.retainedAfterOwnPrompt = replyText(sender.events) === reverseMarker &&
        replyText(observer.events) === reverseMarker;
    }
    results.result = results.senderReply && results.passiveLiveReply && results.restoredExactlyOnce ? 'PASS' : 'FAIL';
    if (process.env.OOB_TEST_SHARED_IDENTITIES === '1' &&
        (!results.backendIdentityPreserved || !results.retainedAfterOwnPrompt)) results.result = 'FAIL';
    if (protocolVersion === 2 && !results.promptAcknowledgedWithoutStopReason) results.result = 'FAIL';
    if (protocolVersion === 2 && !results.passiveUserMessage) results.result = 'FAIL';
    console.log(JSON.stringify(results));
    if (results.result !== 'PASS') process.exitCode = 1;
  } catch (e) { console.error(JSON.stringify({ ...results, result: 'ERROR', error: e.message })); process.exitCode = 1; }
  finally { for (const p of peers) p.stop(); }
})();
