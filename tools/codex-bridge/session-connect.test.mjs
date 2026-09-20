import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp, writeFile, readFile, stat, rm} from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import {once} from 'node:events';
import {fileURLToPath} from 'node:url';
import {WebSocketServer} from 'ws';
import {Client} from '@modelcontextprotocol/sdk/client/index.js';
import {StdioClientTransport} from '@modelcontextprotocol/sdk/client/stdio.js';

test('MCP connects only an owned running session without loading history or sending', {timeout: 15000}, async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'oob-connect-test-'));
  const backend = new WebSocketServer({host: '127.0.0.1', port: 0});
  await once(backend, 'listening');
  const calls = [];
  backend.on('connection', socket => socket.on('message', bytes => {
    const request = JSON.parse(bytes);
    calls.push(request);
    if (!request.id) return;
    let result;
    if (request.method === 'initialize') result = {};
    else if (request.method === 'thread/loaded/list') result = {data: ['owned'], nextCursor: null};
    else if (request.method === 'thread/read') result = {thread: {id: 'owned', cwd: '/fixture/project'}};
    else { socket.send(JSON.stringify({id: request.id, error: {message: 'Unexpected mutation'}})); return; }
    socket.send(JSON.stringify({id: request.id, result}));
  }));
  const tokenFile = path.join(directory, 'token');
  const configPath = path.join(directory, 'config.json');
  await writeFile(tokenFile, 'fixture-private-credential', {mode: 0o600});
  await writeFile(configPath, JSON.stringify({publicUrl: 'wss://bridge.example.com/codex',
    backendUrl: `ws://127.0.0.1:${backend.address().port}`, tokenFile, outputDir: directory,
    desktopSshHost: 'fixture-computer'}), {mode: 0o600});
  const client = new Client({name: 'connection-regression', version: '1'});
  try {
    await client.connect(new StdioClientTransport({command: process.execPath,
      args: [fileURLToPath(new URL('./session-connect-mcp.mjs', import.meta.url))],
      env: {...process.env, OMNIBOT_CONNECT_CONFIG: configPath}, stderr: 'pipe'}));
    const {tools} = await client.listTools();
    assert.equal(tools[0].name, 'connect_session_to_xiaowan');
    const rejected = await client.callTool({name: tools[0].name, arguments: {sessionId: 'other-backend'}});
    assert.equal(rejected.isError, true);
    assert.equal(JSON.parse(rejected.content[0].text).code, 'SESSION_NOT_LOADED_ON_BACKEND');
    assert(!calls.some(call => call.method === 'thread/read'));
    const accepted = await client.callTool({name: tools[0].name, arguments: {sessionId: 'owned'}});
    assert.equal(accepted.isError, false);
    const data = JSON.parse(accepted.content[0].text);
    assert.equal(data.sessionId, 'owned');
    assert.equal(data.desktop.sshHost, 'fixture-computer');
    assert.equal(data.desktop.cwd, '/fixture/project');
    assert.equal(data.desktop.sessionId, data.sessionId);
    assert(!JSON.stringify(data.desktop).includes('codex://threads/'));
    assert(!JSON.stringify(accepted).includes('fixture-private-credential'));
    assert.deepEqual((await readFile(data.qrPath)).subarray(0, 8), Buffer.from([137,80,78,71,13,10,26,10]));
    assert.equal((await stat(data.qrPath)).mode & 0o777, 0o600);
    assert.equal(calls.find(call => call.method === 'thread/read').params.includeTurns, false);
    assert(calls.every(call => ['initialize', 'initialized', 'thread/loaded/list', 'thread/read'].includes(call.method)));
  } finally {
    await client.close();
    for (const socket of backend.clients) socket.terminate();
    await new Promise(resolve => backend.close(resolve));
    await rm(directory, {recursive: true, force: true});
  }
});
