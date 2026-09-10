import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {respondXiaowanFailure} from './xiaowan-failure-scenarios.mjs';

async function fixture(t) {
  const logs = [];
  const server = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    if (!respondXiaowanFailure(req, res, JSON.parse(Buffer.concat(chunks)), row => logs.push(JSON.parse(row)))) res.writeHead(404).end();
  });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  t.after(() => {server.closeAllConnections(); server.close();});
  return {logs, send: (marker, signal) => fetch(`http://127.0.0.1:${server.address().port}`, {
    method:'POST', signal, body:JSON.stringify({messages:[{role:'user',content:`Reply ${marker}`}]})
  })};
}

test('held stream retains full unique marker and releases resources on cancellation', async t => {
  const {send, logs} = await fixture(t);
  const abort = new AbortController();
  const marker = 'OOB_FAILURE_WAIT_3_1789000000000';
  const response = await send(marker, abort.signal);
  const reader = response.body.getReader();
  const first = await reader.read();
  assert.match(new TextDecoder().decode(first.value), new RegExp(marker+'_WAITING'));
  assert.equal(first.done, false);
  abort.abort();
  await assert.rejects(reader.read());
  for(let i=0;i<40 && !logs.some(row=>row.phase==='response-closed');i++) await new Promise(r=>setTimeout(r,10));
  assert(logs.some(row=>row.marker===marker && row.phase==='response-closed' && row.completed===false));
  const next = await send('OOB_FAILURE_OK_3_1789000000000');
  assert.match(await next.text(), /OOB_FAILURE_OK_3_1789000000000_DONE/);
});

test('quota, authentication and rate limiting remain distinct provider errors', async t => {
  const {send} = await fixture(t);
  for (const [kind,status,code] of [['AUTH',401,'auth'],['QUOTA',429,'insufficient_quota'],['RATE',429,'rate_limit_exceeded']]) {
    const response = await send(`OOB_FAILURE_${kind}_1789000000000`);
    assert.equal(response.status,status);
    assert.equal((await response.json()).error.code,code);
  }
});

test('in-band errors retain partial content or tool input and await client cancellation', async t => {
  const {send,logs}=await fixture(t);
  for(const kind of ['STREAMERROR','STREAMTOOL','STREAMRATE','STREAMLIMIT','STREAMAUTH','STREAMSERVICE','STREAMREJECT','STREAMMODEL']) {
    const abort=new AbortController();
    const marker=`OOB_FAILURE_${kind}_1789000000000`;
    const response=await send(marker,abort.signal);
    const reader=response.body.getReader(); let body='';
    while(!body.includes('"error":')) {
      const chunk=await reader.read(); assert.equal(chunk.done,false);
      body+=new TextDecoder().decode(chunk.value);
    }
    assert(!body.includes('[DONE]'));
    assert(body.includes(kind!=='STREAMTOOL'?'_PARTIAL':'file_write'));
    abort.abort();
    await assert.rejects(reader.read());
  }
  for(let i=0;i<40 && logs.filter(r=>r.phase==='response-closed').length<8;i++) await new Promise(r=>setTimeout(r,10));
  assert.equal(logs.filter(r=>r.phase==='response-closed').length,8);
});
