// Real official Kimi ACP + generated Android adapter configs + local wire fixture.
// Usage: node scripts/verify-kimi-model-catalog.mjs CLI_DIRECTORY FIXTURE_DIRECTORY
// No real credentials or model inference. Checks same-session switching and restart.
import {spawn} from 'node:child_process';
import {createServer} from 'node:http';
import {mkdtemp, mkdir, readFile, writeFile, readdir} from 'node:fs/promises';
import {join, resolve, dirname} from 'node:path';
import {tmpdir} from 'node:os';
import assert from 'node:assert/strict';
import {respondHarnessSuccess} from './harness-success-fixture.mjs';

const [cliDirectory, fixtureDirectory] = process.argv.slice(2);
assert(cliDirectory && fixtureDirectory, 'CLI and generated fixture directories required');
const root = await mkdtemp(join(tmpdir(), 'oob-kimi-catalog-'));
const files = (await readdir(fixtureDirectory)).filter(f => f.startsWith('kimi-code-') && f.endsWith('.json'));
assert.equal(files.length, 3);
for (const file of files) {
  const fixture = JSON.parse(await readFile(join(fixtureDirectory, file), 'utf8'));
  const home = join(root, file); await mkdir(home);
  const observed = [];
  const server = createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString());
    observed.push({model: body.model, path: req.url});
    respondHarnessSuccess(req, res, body);
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const rewrite = value => value.replaceAll('https://fixture.invalid', `http://127.0.0.1:${server.address().port}`).replaceAll('/root', home);
  const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(KIMI|OPENAI|ANTHROPIC)/.test(key)));
  Object.assign(env, {HOME: home}, Object.fromEntries(Object.entries(fixture.environment).map(([k, v]) => [k, rewrite(v)])));
  const configPath = rewrite(fixture.files[0].path);
  await mkdir(dirname(configPath), {recursive: true});
  await writeFile(configPath, rewrite(fixture.files[0].content), {mode: 0o600});
  const start = () => {
    const child = spawn(resolve(cliDirectory, 'node_modules/.bin/kimi'), ['acp'], {cwd: home, env, stdio: ['pipe', 'pipe', 'pipe']});
    let next = 0, buffer = '', stderr = '';
    const pending = new Map();
    child.stderr.on('data', chunk => { stderr = (stderr + chunk).slice(-1000); });
    child.stdout.on('data', chunk => {
      buffer += chunk; const lines = buffer.split('\n'); buffer = lines.pop();
      for (const line of lines) {
        let message; try { message = JSON.parse(line); } catch { continue; }
        if (pending.has(message.id)) {
          pending.get(message.id)(message); pending.delete(message.id);
        }
      }
    });
    return {
      async call(method, params) {
        let timer;
        try {
          const response = await Promise.race([
            new Promise(resolve => {const id = ++next; pending.set(id, resolve); child.stdin.write(JSON.stringify({jsonrpc: '2.0', id, method, params}) + '\n');}),
            new Promise((_, reject) => {timer = setTimeout(() => reject(Error(`${method} timed out: ${stderr}`)), 20000);}),
          ]);
          assert.equal(response.error, undefined, JSON.stringify(response.error));
          return response.result;
        } finally {clearTimeout(timer);}
      },
      async stop() {child.kill(); await new Promise(r => child.once('close', r));},
    };
  };
  let client = start();
  const models = result => result.configOptions.find(o => o.category === 'model');
  const prompt = async sessionId => assert.equal((await client.call('session/prompt', {sessionId, prompt: [{type: 'text', text: 'Reply OK'}]})).stopReason, 'end_turn');
  try {
    await client.call('initialize', {protocolVersion: 1, clientCapabilities: {}});
    const session = await client.call('session/new', {cwd: home, mcpServers: []});
    const sessionId = session.sessionId;
    assert.deepEqual(models(session).options.map(o => o.value).sort(), ['org/second-model', 'org/test-model']);
    await prompt(sessionId);
    const switched = await client.call('session/set_config_option', {sessionId, configId: 'model', value: 'org/second-model'});
    assert.equal(models(switched).currentValue, 'org/second-model');
    await prompt(sessionId);
    // Simulate explicit catalog discovery through the same owned config file.
    await writeFile(configPath, (await readFile(configPath, 'utf8')) + '\n[models."org/new-model"]\nprovider="omnibot"\nmodel="org/new-model"\nmax_context_size=262144\ncapabilities=["thinking"]\n');
    const refreshed = await client.call('session/load', {sessionId, cwd: home, mcpServers: []});
    assert.equal(models(refreshed).currentValue, 'org/second-model');
    assert(models(refreshed).options.some(o => o.value === 'org/new-model'));
    await client.call('session/set_config_option', {sessionId, configId: 'model', value: 'org/new-model'});
    await prompt(sessionId);
    await client.stop(); client = start();
    await client.call('initialize', {protocolVersion: 1, clientCapabilities: {}});
    const restored = await client.call('session/load', {sessionId, cwd: home, mcpServers: []});
    assert.equal(models(restored).currentValue, 'org/new-model');
    await prompt(sessionId);
    assert.deepEqual(observed.map(o => o.model), ['org/test-model', 'org/second-model', 'org/new-model', 'org/new-model']);
    const path = fixture.protocol === 'anthropic' ? '/v1/messages' : fixture.wire === 'responses' ? '/v1/responses' : '/v1/chat/completions';
    assert(observed.every(o => new URL(o.path, "http://fixture").pathname === path), JSON.stringify({expected: path, observed}));
    console.log(JSON.stringify({case: file, sameSession: true, requests: observed.length, refresh: true, restart: true, passed: true}));
  } finally {
    await client.stop(); server.closeAllConnections(); await new Promise(r => server.close(r));
  }
}
