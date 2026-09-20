#!/usr/bin/env node
// Read-only actual phone UI + authoritative backend check for isolated markers.
const { execFileSync } = require('node:child_process');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const WebSocket = require('ws');
const [serial, sessionId, ...markers] = process.argv.slice(2);
assert(serial && sessionId && markers.length && markers.every(x => /^OOB_[A-Z0-9_]+$/.test(x)));
const url = process.env.OOB_SHARED_CODEX_URL;
assert(url, 'Explicit backend required');
const token = process.env.OOB_SHARED_CODEX_TOKEN_FILE && fs.readFileSync(process.env.OOB_SHARED_CODEX_TOKEN_FILE, 'utf8').trim();
const ws = new WebSocket(url, { perMessageDeflate: false, ...(token ? { headers: { Authorization: `Bearer ${token}` } } : {}) });
const pending = new Map(); let seq = 0;
const timer = setTimeout(() => { ws.terminate(); console.error('Backend timed out'); process.exitCode = 1; }, 15000);
ws.on('error', error => { console.error(error.message); process.exitCode = 1; clearTimeout(timer); });
ws.on('message', bytes => {
  const msg = JSON.parse(bytes); const waiter = pending.get(msg.id);
  if (waiter) { pending.delete(msg.id); msg.error ? waiter.reject(Error(JSON.stringify(msg.error))) : waiter.resolve(msg.result); }
});
function rpc(method, params) {
  return new Promise((resolve, reject) => {
    const id = ++seq; pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
}
ws.on('open', async () => {
  try {
    await rpc('initialize', { clientInfo: { name: 'oob-physical-history-verifier', version: '1' } });
    const result = await rpc('thread/read', { threadId: sessionId, includeTurns: true });
    assert.equal(result.thread.id, sessionId);
    assert(/\/oob-shared-session-probe$/.test(result.thread.cwd), 'Only isolated test workspace allowed');
    const items = result.thread.turns.flatMap(turn => turn.items);
    for (const marker of markers) {
      assert.equal(items.filter(item => item.type === 'userMessage' && item.content?.some(c => c.text?.includes(marker))).length, 1, 'Exactly one backend user message: ' + marker);
      assert.equal(items.filter(item => item.type === 'agentMessage' && item.text === marker).length, 1, 'Exactly one backend reply: ' + marker);
    }
    clearTimeout(timer);
    const python = `import subprocess,sys,xml.etree.ElementTree as E,json
base=['adb','-s',sys.argv[1]]
markers=sys.argv[2:]
texts=[]
for attempt in range(6):
 subprocess.run(base+['shell','rm','-f','/sdcard/oob-regression.xml'],check=True,timeout=3)
 dumped=subprocess.check_output(base+['shell','uiautomator','dump','/sdcard/oob-regression.xml'],timeout=5)
 if b'dumped to' not in dumped: continue
 raw=subprocess.check_output(base+['shell','cat','/sdcard/oob-regression.xml'],timeout=3)
 texts=[n.get('text') or n.get('content-desc') or '' for n in E.fromstring(raw).iter('node')]
 if all(any(t==m or t.startswith(m+'\\n') for t in texts) for m in markers):break
print(json.dumps(texts))`;
    const texts = JSON.parse(execFileSync('python3', ['-c', python, serial, ...markers], { encoding: 'utf8', timeout: 50000 }));
    for (const marker of markers) {
      assert.equal(texts.filter(text => text.includes('Reply only ' + marker)).length, 1, 'Exactly one visible phone user message: ' + marker);
      assert.equal(texts.filter(text => text === marker || text.startsWith(marker + '\n')).length, 1, 'Exactly one visible phone reply: ' + marker);
    }
    console.log(JSON.stringify({ result: 'PASS', serial, sessionId, turnIds: result.thread.turns.map(t => t.id), markers, acceptance: 'actual phone visible history matches backend; native desktop display and WAN require separate verification' }));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
  finally { clearTimeout(timer); ws.close(); }
});
