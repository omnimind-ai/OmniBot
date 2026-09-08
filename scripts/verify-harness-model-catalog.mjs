// Real official Harness ACP + generated Android adapter configs + local wire fixture.
// Usage: node scripts/verify-harness-model-catalog.mjs CLI_DIRECTORY FIXTURE_DIRECTORY
// No real credentials or model inference. Checks same-session switching and restart.
import {spawn} from 'node:child_process';
import {createServer} from 'node:http';
import {mkdtemp, mkdir, readFile, writeFile, readdir} from 'node:fs/promises';
import {join, resolve, dirname} from 'node:path';
import {tmpdir} from 'node:os';
import assert from 'node:assert/strict';
import {respondHarnessSuccess} from './harness-success-fixture.mjs';

const [cliDirectory, fixtureDirectory, filter = ""] = process.argv.slice(2);
assert(cliDirectory && fixtureDirectory, 'CLI and generated fixture directories required');
const root = await mkdtemp(join(tmpdir(), 'oob-kimi-catalog-'));
const files = (await readdir(fixtureDirectory)).filter(f => f.includes(filter) && f.endsWith('.json'));
assert(files.length > 0);
for (const file of files) {
  const fixture = JSON.parse(await readFile(join(fixtureDirectory, file), 'utf8'));
  assert(fixture.refreshFiles, 'Regenerate adapter fixtures before running catalog tests');
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
  const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(KIMI|OPENAI|ANTHROPIC|CLAUDE|CODEX|DEEPSEEK|DSH|OPENCODE)/.test(key)));
  Object.assign(env, {HOME: home}, Object.fromEntries(Object.entries(fixture.environment).map(([k, v]) => [k, rewrite(v)])));
  const writeConfigs = async files => {
    for (const file of files) {
      const path = rewrite(file.path);
      await mkdir(dirname(path), {recursive: true});
      await writeFile(path, rewrite(file.content), {mode: 0o600});
    }
  };
  await writeConfigs(fixture.files);
  Object.assign(env, {CLAUDE_CONFIG_DIR: join(home, '.claude'), OPENCODE_DISABLE_MODELS_FETCH: 'true',
    PATH: resolve(cliDirectory, 'node_modules/.bin') + ':' + env.PATH});
  const commands = {
    'kimi-code': ['kimi', 'acp'], 'claude-code': ['claude-agent-acp'], 'codex': ['codex-acp'],
    'open-code': ['opencode', 'acp'], 'deepseek-harness': ['dsh', '--profile', 'acp', '--patch', rewrite(fixture.files[0]?.path ?? "")],
  };
  const [command, ...args] = commands[fixture.harness];
  if (fixture.harness === 'open-code') env.OPENCODE_CONFIG = rewrite(fixture.files[0].path);
  if (fixture.harness === 'deepseek-harness') env.DSH_HOME = join(home, '.dsh');
  const start = () => {
    const child = spawn(resolve(cliDirectory, 'node_modules/.bin', command), args, {cwd: home, env, stdio: ['pipe', 'pipe', 'pipe']});
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
  const options = result => models(result).options.flatMap(o => o.options ?? [o]);
  const modelValue = (result, id) => {
    const item = options(result).find(o => {
      try { const parts = JSON.parse(o.value); if (Array.isArray(parts)) return parts.at(-1) === id; } catch {}
      return o.value.endsWith(id);
    });
    assert(item, `${fixture.harness}: Provider model ${id} absent; options=${JSON.stringify(options(result))}`);
    return item.value;
  };
  const prompt = async sessionId => assert.equal((await client.call('session/prompt', {sessionId, prompt: [{type: 'text', text: 'Reply OK'}]})).stopReason, 'end_turn');
  try {
    await client.call('initialize', {protocolVersion: 1, clientCapabilities: {_meta: fixture.clientCapabilityMeta || {}}});
    const session = await client.call('session/new', {cwd: home, mcpServers: []});
    const sessionId = session.sessionId;
    modelValue(session, 'org/test-model');
    const second = modelValue(session, 'org/second-model');
    await prompt(sessionId);
    const switched = await client.call('session/set_config_option', {sessionId, configId: models(session).id, value: second});
    assert.equal(models(switched).currentValue, second);
    await prompt(sessionId);
    // Simulate explicit catalog discovery through the same owned config file.
    await writeConfigs(fixture.refreshFiles);
    // Diagnostic option to distinguish watch propagation from a stale catalog.
    if (process.env.OOB_CATALOG_WATCH_SETTLE_MS) await new Promise(r => setTimeout(r, Number(process.env.OOB_CATALOG_WATCH_SETTLE_MS)));
    const refreshed = await client.call('session/load', {sessionId, cwd: home, mcpServers: []});
    assert.equal(models(refreshed).currentValue, second);
    const added = modelValue(refreshed, 'org/new-model');
    await client.call('session/set_config_option', {sessionId, configId: models(refreshed).id, value: added});
    await prompt(sessionId);
    await client.stop(); client = start();
    await client.call('initialize', {protocolVersion: 1, clientCapabilities: {_meta: fixture.clientCapabilityMeta || {}}});
    const restored = await client.call('session/load', {sessionId, cwd: home, mcpServers: []});
    assert.equal(models(restored).currentValue, added);
    await prompt(sessionId);
    assert.deepEqual(observed.map(o => o.model), ['org/test-model', 'org/second-model', 'org/new-model', 'org/new-model']);
    const path = fixture.protocol === 'anthropic' ? '/v1/messages' : fixture.harness === 'codex' || fixture.wire === 'responses' ? '/v1/responses' : '/v1/chat/completions';
    assert(observed.every(o => new URL(o.path, "http://fixture").pathname === path), JSON.stringify({expected: path, observed}));
    console.log(JSON.stringify({case: file, sameSession: true, requests: observed.length, refresh: true, restart: true, passed: true}));
  } finally {
    await client.stop(); server.closeAllConnections(); await new Promise(r => server.close(r));
  }
}
