import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {once} from 'node:events';
import {createProviderObserver} from './agent-provider-observer.mjs';

test('observer forwards model selection, credentials and SSE without logging private input', async t => {
  const requests = [];
  const upstream = http.createServer(async (req, res) => {
    let body = '';
    for await (const chunk of req) body += chunk;
    requests.push({body: JSON.parse(body), authorization: req.headers.authorization});
    res.writeHead(200, {'content-type': 'text/event-stream'});
    res.write('data: {"delta":"OK"}\n\n');
    res.end('data: [DONE]\n\n');
  });
  upstream.listen(0, '127.0.0.1');
  await once(upstream, 'listening');
  const events = [];
  const observer = createProviderObserver(`http://127.0.0.1:${upstream.address().port}`, e => events.push(e));
  observer.listen(0, '127.0.0.1');
  await once(observer, 'listening');
  t.after(() => { observer.closeAllConnections(); observer.close(); upstream.closeAllConnections(); upstream.close(); });
  for (const model of ['model-a', 'model-b', 'model-a']) {
    const response = await fetch(`http://127.0.0.1:${observer.address().port}/v1/chat/completions`, {
      method: 'POST', headers: {'authorization': 'Bearer test-secret', 'content-type': 'application/json'},
      body: JSON.stringify({model,
        ...(model === 'model-b' ? {output_config: {effort: 'high'}} : {reasoning_effort: 'high'}),
        messages: [{role: 'user', content: 'private prompt'}], stream: true}),
    });
    assert.equal(await response.text(), 'data: {"delta":"OK"}\n\ndata: [DONE]\n\n');
  }
  assert.deepEqual(requests.map(r => r.body.model), ['model-a', 'model-b', 'model-a']);
  assert(requests.every(r => r.authorization === 'Bearer test-secret'));
  assert.deepEqual(events.filter(e => e.phase === 'request').map(e => e.model), ['model-a', 'model-b', 'model-a']);
  assert.equal(events.filter(e => e.phase === 'response').length, 3);
  assert(events.filter(e => e.phase === 'request').every(e => e.effort === 'high'));
  assert(requests.every(r => (r.body.reasoning_effort ?? r.body.output_config?.effort) === 'high'));
  assert(!JSON.stringify(events).includes('test-secret'));
  assert(!JSON.stringify(events).includes('private prompt'));
});

test('upstream must not embed credentials or a rewritten endpoint', () => {
  for (const origin of ['https://user:secret@example.com', 'https://example.com/v1', 'https://example.com?token=secret']) {
    assert.throws(() => createProviderObserver(origin));
  }
});
