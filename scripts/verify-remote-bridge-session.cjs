// Run with NODE_PATH pointing at the existing Bridge dependencies.
// Credentials stay outside argv and output. This is a protocol test, not phone acceptance.
const {readFileSync} = require('node:fs');
const assert = require('node:assert/strict');
const WebSocket = require('ws');
const url = process.env.OOB_BRIDGE_URL;
const token = readFileSync(process.env.OOB_BRIDGE_TOKEN_FILE, 'utf8').trim();
const cwd = process.env.OOB_BRIDGE_CWD;
assert(url && token && cwd, 'Bridge URL, token file and cwd are required');
async function connect(auth) {
  const ws = new WebSocket(url, {headers: {Authorization: `Bearer ${auth}`}});
  const pending = new Map(); let id = 0;
  const updates = [];
  const hello = new Promise((resolve, reject) => {
    ws.once('error', reject);
    ws.on('open', () => ws.send(JSON.stringify({type:'hello',protocol:'acp',cwd,token:auth})));
    ws.on('message', raw => {
      const envelope = JSON.parse(String(raw));
      if (envelope.type === 'hello') resolve(envelope);
      if (envelope.type !== 'stdout') return;
      let message; try { message = JSON.parse(envelope.line); } catch { return; }
      if (message.method === 'session/update') updates.push(message.params);
      const p = pending.get(message.id);
      if (p) { pending.delete(message.id); message.error ? p.reject(new Error(JSON.stringify(message.error))) : p.resolve(message.result); }
    });
    ws.on('close', () => {
      reject(new Error('Bridge closed before hello'));
      for (const p of pending.values()) p.reject(new Error('Bridge closed'));
      pending.clear();
    });
  });
  function request(method, params) {
    return new Promise((resolve,reject) => {
      const n=++id;pending.set(n,{resolve,reject});
      ws.send(JSON.stringify({type:'stdin',line:JSON.stringify({jsonrpc:'2.0',id:n,method,params})}));
    });
  }
  return {ws,hello,request,updates};
}
(async () => {
  const timeout=setTimeout(()=>{console.error('FAIL: protocol test timed out');process.exit(1);},150000);
  const denied=await connect('invalid-test-token');
  assert.equal((await denied.hello).ok,false);denied.ws.close();
  console.log('PASS unauthorized hello rejected');
  let c=await connect(token);
  try {
    assert.equal((await c.hello).ok,true);
    const init=await c.request('initialize',{protocolVersion:1,clientInfo:{name:'oob-remote-regression',version:'1'},clientCapabilities:{}});
    assert.equal(init.protocolVersion,1);
    console.log('PASS ACP initialize');
    const created=await c.request('session/new',{cwd,mcpServers:[]});
    assert(created.sessionId);
    console.log('PASS session/new');
    const reply=await c.request('session/prompt',{sessionId:created.sessionId,prompt:[{type:'text',text:'Reply only OOB_REMOTE_SESSION_OK. Do not use tools.'}]});
    assert.equal(reply.stopReason,'end_turn');
    console.log('PASS real model turn completed');
    const listed=await c.request('session/list',{});
    assert(Array.isArray(listed.sessions));
    console.log('PASS session/list');
    assert.equal(init.agentCapabilities?.loadSession,true,'session/load not advertised');
    c.ws.close();
    await new Promise(resolve=>c.ws.once('close',resolve));
    c=await connect(token);assert.equal((await c.hello).ok,true);
    await c.request('initialize',{protocolVersion:1,clientInfo:{name:'oob-remote-regression',version:'1'},clientCapabilities:{}});
    await c.request('session/load',{sessionId:created.sessionId,cwd,mcpServers:[]});
    console.log('PASS new connection loads same persisted session; no prompt replay');
    c.updates.length=0;
    const continued=await c.request('session/prompt',{sessionId:created.sessionId,prompt:[{type:'text',text:'Repeat only the exact marker from my previous request. Do not use tools.'}]});
    assert.equal(continued.stopReason,'end_turn');
    const text=c.updates.filter(p=>p.update?.sessionUpdate==='agent_message_chunk').map(p=>p.update?.content?.text || '').join('');
    assert(text.includes('OOB_REMOTE_SESSION_OK'), 'Restored session did not return previous marker');
    console.log('PASS continued restored conversation retains prior context');
  } finally {c.ws.close();clearTimeout(timeout);}
})().catch(error=>{console.error('FAIL:',error.message);process.exit(1);});
