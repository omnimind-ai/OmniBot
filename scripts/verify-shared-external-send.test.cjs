const test = require('node:test');
const assert = require('node:assert/strict');
const {WebSocketServer} = require('ws');
const {spawn} = require('node:child_process');
const {once} = require('node:events');
const path = require('node:path');

test('live-send verifier waits for official completion instead of partial persisted status', {timeout:10000}, async () => {
  const server = new WebSocketServer({host:'127.0.0.1', port:0});
  await once(server,'listening');
  let started = false, done = false, reads = 0, sends = 0;
  const marker = 'OOB_FIXTURE_TERMINAL';
  server.on('connection', socket => socket.on('message', bytes => {
    const request = JSON.parse(bytes);
    const reply = result => socket.send(JSON.stringify({id:request.id,result}));
    if (request.method === 'initialize') reply({});
    else if (request.method === 'thread/resume') reply({});
    else if (request.method === 'thread/read') {
      reads++;
      reply({thread:{cwd:'/tmp/oob-shared-session-probe', turns:started ? [{
        id:'turn',status:done ? 'completed' : 'interrupted',
        items:done ? [{type:'agentMessage',text:marker}] : [],
      }] : []}});
    } else if (request.method === 'turn/start') {
      started = true; sends++;
      reply({turn:{id:'turn',status:'inProgress'}});
      setTimeout(() => {
        done = true;
        socket.send(JSON.stringify({method:'turn/completed',params:{threadId:'fixture',turn:{id:'turn',status:'completed'}}}));
      },150);
    }
  }));
  let child;
  try {
    child = spawn(process.execPath,[path.join(__dirname,'verify-shared-external-send.cjs'),'fixture',marker],
      {env:{...process.env,OOB_SHARED_CODEX_URL:`ws://127.0.0.1:${server.address().port}`}});
    let stdout='',stderr='';
    child.stdout.on('data',b=>stdout+=b); child.stderr.on('data',b=>stderr+=b);
    const [code] = await once(child,'exit');
    assert.equal(code,0,stderr);
    assert.equal(JSON.parse(stdout).result,'PASS');
    assert.equal(sends,1);
    assert.equal(reads,2,'Only baseline and post-completion history reads');
  } finally {
    if (child?.exitCode == null) child?.kill();
    for (const socket of server.clients) socket.terminate();
    await new Promise(resolve=>server.close(resolve));
  }
});
