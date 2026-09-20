#!/usr/bin/env node
// Protocol-originated send to the phone's isolated session; not native UI QA.
const assert = require('node:assert/strict');
const WebSocket = require('ws');
const {realpathSync} = require('node:fs');
const [threadId, marker] = process.argv.slice(2);
assert(threadId && /^OOB_[A-Z0-9_]+$/.test(marker));
assert(process.env.OOB_SHARED_CODEX_URL);
const ws = new WebSocket(process.env.OOB_SHARED_CODEX_URL, { perMessageDeflate: false });
const pending = new Map(); let seq = 0;
const completed = new Map();
let completionWaiter;
function waitForCompletion(turnId) {
  if (completed.has(turnId)) return Promise.resolve(completed.get(turnId));
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      completionWaiter = null;
      reject(Error('Completion deadline exceeded; inspect same turn, do not resend'));
    }, 60000);
    completionWaiter = {turnId, resolve: turn => {
      clearTimeout(timer); completionWaiter = null; resolve(turn);
    }};
  });
}
function rpc(method, params) {
  return new Promise((resolve, reject) => {
    const id = ++seq;
    const timer = setTimeout(() => { pending.delete(id); reject(Error('RPC timeout')); }, 10000);
    pending.set(id, { resolve, reject, timer });
    ws.send(JSON.stringify({id, method, params}));
  });
}
ws.on('message', bytes => {
  const m = JSON.parse(bytes); const p = pending.get(m.id);
  if (m.method === 'turn/completed' && m.params?.threadId === threadId) {
    const turn = m.params.turn;
    completed.set(turn.id, turn);
    if (completionWaiter?.turnId === turn.id) completionWaiter.resolve(turn);
  }
  if (!p) return;
  clearTimeout(p.timer); pending.delete(m.id);
  m.error ? p.reject(Error(JSON.stringify(m.error))) : p.resolve(m.result);
});
ws.on('error', e => { console.error(e.message); process.exitCode = 1; });
ws.on('open', async () => {
  try {
    await rpc('initialize', {clientInfo:{name:'oob-external-send-probe',version:'1'}});
    const read = async () => (await rpc('thread/read', {threadId,includeTurns:true})).thread;
    const baseline = await read();
    if (process.env.OOB_TEST_CWD) {
      assert.equal(realpathSync(baseline.cwd), realpathSync(process.env.OOB_TEST_CWD),
        'Selected session must belong to the explicitly configured test workspace');
    } else {
      assert(/\/oob-shared-session-probe$/.test(baseline.cwd));
    }
    assert(!JSON.stringify(baseline.turns).includes(marker), 'Fresh marker required; never resend');
    await rpc('thread/resume', {threadId});
    const started = await rpc('turn/start', {threadId,input:[{type:'text',text:`Reply only ${marker}. Do not use tools.`,text_elements:[]}]});
    const terminal = await waitForCompletion(started.turn.id);
    assert.equal(terminal.status, 'completed');
    const thread = await read(); const turn = thread.turns.find(t=>t.id===started.turn.id);
    assert(turn, 'Completed turn missing from history');
    assert.equal(turn.status,'completed');
        assert.equal(thread.turns.length,baseline.turns.length+1);
        assert.equal(turn.items.filter(i=>i.type==='agentMessage' && i.text===marker).length,1);
        console.log(JSON.stringify({result:'PASS',threadId,turnId:turn.id,marker,acceptance:'external protocol send only; inspect actual phone live display separately'}));
  } catch(e) {console.error(e.message);process.exitCode=1;}
  finally {ws.close();}
});
