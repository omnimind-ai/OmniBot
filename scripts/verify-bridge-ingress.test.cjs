const {test} = require('node:test');
const assert = require('node:assert/strict');
const {WebSocketServer} = require('ws');
const {createServer} = require('node:http');
const {probeBridge} = require('./verify-bridge-ingress.cjs');
async function fixture({acceptInvalid = false, version = 2, healthRoute = true, filesRoute = true} = {}) {
  const http = createServer((req, res) => {
    res.setHeader('content-type', 'application/json');
    res.statusCode = !healthRoute || (!filesRoute && req.url.startsWith('/fs/'))
      ? 404 : req.headers.authorization === 'Bearer fixture-secret' ? 200 : 401;
    res.end(JSON.stringify({ready: res.statusCode === 200, ok: res.statusCode === 200}));
  });
  const server = new WebSocketServer({server: http});
  await new Promise(resolve => http.listen(0, '127.0.0.1', resolve));
  const methods = [];
  server.on('connection', ws => ws.on('message', raw => {
    const e = JSON.parse(raw);
    if (e.type === 'hello') {
      ws.send(JSON.stringify({type: 'hello', ok: acceptInvalid || e.token === 'fixture-secret'}));
    } else if (e.type === 'stdin') {
      const rpc = JSON.parse(e.line); methods.push(rpc.method);
      ws.send(JSON.stringify({type: 'stdout', line: JSON.stringify({id: rpc.id, result: {protocolVersion: version}})}));
    }
  }));
  return {methods, url: `ws://127.0.0.1:${http.address().port}/codex`,
    close: () => new Promise(resolve => {for (const ws of server.clients) ws.terminate(); server.close(() => http.close(resolve));})};
}
test('real WebSocket fixture tests authenticated concurrent ACP, never sends a prompt', async () => {
  const f = await fixture();
  try {
    const r = await probeBridge({url: f.url, token: 'fixture-secret', cwd: '/tmp', connections: 3});
    assert.equal(r.authenticatedConnections, 3);
    assert.equal(r.encrypted, false);
    assert.deepEqual(f.methods, ['initialize', 'initialize', 'initialize']);
  } finally {await f.close();}
});
test('fails when ingress accepts invalid credentials', async () => {
  const f = await fixture({acceptInvalid: true});
  try {await assert.rejects(probeBridge({url: f.url, token: 'fixture-secret', cwd: '/tmp'}), /Invalid credentials/);}
  finally {await f.close();}
});
test('does not count wrong protocol as success', async () => {
  const f = await fixture({version: 1});
  try {await assert.rejects(probeBridge({url: f.url, token: 'fixture-secret', cwd: '/tmp'}), /connections failed/);}
  finally {await f.close();}
});
test('WebSocket-only routing cannot pass Android ingress acceptance', async () => {
  const f = await fixture({healthRoute: false});
  try {await assert.rejects(probeBridge({url: f.url, token: 'fixture-secret', cwd: '/tmp'}), /health route unavailable/);}
  finally {await f.close();}
});
test('missing existing file API fails ingress acceptance', async () => {
  const f = await fixture({filesRoute: false});
  try {await assert.rejects(probeBridge({url: f.url, token: 'fixture-secret', cwd: '/tmp'}), /file listing route unavailable/);}
  finally {await f.close();}
});
