// Consume config fixtures emitted by AgentAdapterCatalogTest and run real ACP CLIs.
// Usage: node scripts/verify-installed-harness-adapters.mjs CLI_DIRECTORY FIXTURE_DIRECTORY [CASE_FILTER]
// Local fake credentials + deterministic success: validates ACP/wire, not inference.
import {spawn} from 'node:child_process';
import {createServer} from 'node:http';
import {mkdtemp, mkdir, readFile, writeFile, readdir} from 'node:fs/promises';
import {join, resolve, dirname} from 'node:path';
import {tmpdir} from 'node:os';
import assert from 'node:assert/strict';
import {respondHarnessSuccess} from './harness-success-fixture.mjs';
const [cliDirectory, fixtureDirectory, filter = '', scenario = 'success'] = process.argv.slice(2);
assert(['success', 'failure', 'completed-text-only', 'partial-text', 'conversation'].includes(scenario));
assert(cliDirectory && fixtureDirectory, 'CLI and fixture directories required');
const bin = resolve(cliDirectory, 'node_modules/.bin');
const root = await mkdtemp(join(tmpdir(), 'oob-acp-matrix-'));
const cases = (await readdir(fixtureDirectory)).filter(f => f.endsWith('.json') && f.includes(filter)).sort();
assert(cases.length, 'No generated adapter fixtures; run AgentAdapterCatalogTest first');
for (const filename of cases) {
  const config = JSON.parse(await readFile(join(fixtureDirectory, filename), 'utf8'));
  if (scenario === 'conversation') assert.equal(config.harness, 'claude-code');
  if (scenario === 'failure') assert.equal(config.harness, 'codex', 'Failure case currently covers Codex');
  let declaredFailure = false, leakedFailureText = false;
  let assistantText = '';
  const turns = [];
  let foreignSessionOutput = false;
  let followupHasHistory = false;
  const home = join(root, filename.replace('.json', ''));
  await mkdir(home, {recursive: true});
  let reached = false, initialized = false, sessionCreated = false, terminal = false;
  let reasoningOptions = 0, requests = 0, wirePath = '';
  let sessionId, thoughtId, thoughtValue, configApplied = null, observedThought = null;
  const server = createServer(async (req, res) => {
    const chunks = []; for await (const chunk of req) chunks.push(chunk);
    if (req.url.endsWith('/models')) {
      res.writeHead(200, {'content-type': 'application/json'});
      res.end(JSON.stringify({data: [{id: 'org/test-model', object: 'model', type: 'model', display_name: 'Test model'}]}));
      return;
    }
    let body; try { body = JSON.parse(Buffer.concat(chunks).toString()); } catch { body = {}; }
    requests++; wirePath = req.url;
    observedThought = body.reasoning?.effort ?? body.reasoning_effort ?? body.output_config?.effort ?? body.thinking?.type ?? null;
    const expected = config.harness === 'claude-code' ? '/v1/messages' : config.harness === 'codex' || config.protocol !== 'anthropic' && config.wire === 'responses'
      ? '/v1/responses' : config.protocol === 'anthropic' ? '/v1/messages' : '/v1/chat/completions';
    if (scenario === 'conversation' && turns.length === 1) {
      followupHasHistory ||= (body.messages || []).some(m => m.role === 'assistant' &&
        (m.content === 'OK' || Array.isArray(m.content) && m.content.some(c => c.type === 'text' && c.text === 'OK')));
    }
    reached ||= new URL(req.url, base).pathname === expected && body.model === 'org/test-model';
    if (scenario === 'failure') {
      res.writeHead(400, {'content-type': 'application/json'});
      res.end(JSON.stringify({error: {type: 'invalid_request_error', message: 'OOB_PRIVATE_FAILURE_DETAIL'}}));
    } else respondHarnessSuccess(req, res, body, {
      completedTextOnly: scenario === 'completed-text-only', partialText: scenario === 'partial-text',
      reply: scenario === 'conversation' && turns.length === 1 ? 'FOLLOWUP_OK' : 'OK',
    });
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}/v1`;
  const rewrite = value => String(value).replaceAll('https://fixture.invalid', base.slice(0, -3)).replaceAll('/root', home);
  const env = Object.fromEntries(Object.entries(process.env).filter(([key]) => !/^(OPENAI|ANTHROPIC|CLAUDE|CODEX|KIMI|DEEPSEEK|DSH|OPENCODE|OMNIBOT_.*KEY)/.test(key)));
  Object.assign(env, {HOME: home, XDG_CONFIG_HOME: join(home, '.config'), PATH: `${bin}:${env.PATH}`,
    CLAUDE_CONFIG_DIR: join(home, '.claude'), OPENCODE_DISABLE_MODELS_FETCH: 'true',
    ...Object.fromEntries(Object.entries(config.environment).map(([k, v]) => [k, rewrite(v)]))});
  for (const file of config.files) {
    const path = rewrite(file.path); await mkdir(dirname(path), {recursive: true});
    await writeFile(path, rewrite(file.content), {mode: 0o600});
  }
  let command, args;
  switch (config.harness) {
    case 'codex': command = 'codex-acp'; args = []; break;
    case 'claude-code': command = 'claude-agent-acp'; args = []; break;
    case 'kimi-code': command = 'kimi'; args = ['acp']; break;
    case 'open-code': command = 'opencode'; args = ['acp'];
      env.OPENCODE_CONFIG = rewrite(config.files[0].path); break;
    case 'deepseek-harness': command = 'dsh'; args = ['--profile', 'acp', '--patch', rewrite(config.files[0].path)];
      env.DSH_HOME = join(home, '.dsh'); break;
    default: throw Error(`Missing CLI for ${config.harness}`);
  }
  const child = spawn(join(bin, command), args, {cwd: home, env, stdio: ['pipe', 'pipe', 'pipe']});
  let pending = '', errorStage = '', stderr = '';
  child.stderr.on('data', chunk => { stderr = (stderr + chunk).slice(-4000); });
  child.stdin.on('error', () => {});
  const send = (id, method, params) => child.stdin.write(JSON.stringify({jsonrpc: '2.0', id, method, params}) + '\n');
  child.stdout.on('data', chunk => {
    pending += chunk; const lines = pending.split('\n'); pending = lines.pop();
    for (const line of lines) {
      let m; try { m = JSON.parse(line); } catch { continue; }
      if (m.params?.update?.sessionUpdate === 'agent_message_chunk' &&
          m.params.update.content?.type === 'text') {
        foreignSessionOutput ||= m.params.sessionId !== sessionId;
        assistantText += m.params.update.content.text;
      }
      if (m.params?.update?.sessionUpdate === 'agent_message_chunk' &&
          JSON.stringify(m.params.update).includes('OOB_PRIVATE_FAILURE_DETAIL')) leakedFailureText = true;
      if (m.method && m.id !== undefined) {
        child.stdin.write(JSON.stringify({jsonrpc: '2.0', id: m.id, error: {code: -32601, message: 'Fixture does not grant tool access'}}) + '\n');
        continue;
      }
      if (m.id === 1) {
        initialized = m.result?.protocolVersion === 1;
        if (initialized) send(2, 'session/new', {cwd: home, mcpServers: []});
        else { errorStage = 'initialize'; child.stdin.end(); }
      } else if (m.id === 2) {
        sessionCreated = !!m.result?.sessionId;
        reasoningOptions = (m.result?.configOptions || []).filter(o => o.category === 'thought_level').length;
        if (sessionCreated) {
          sessionId = m.result.sessionId;
          const thought = (m.result.configOptions || []).find(o => o.category === 'thought_level');
          const choices = (thought?.options || []).flatMap(o => o.options || [o]);
          const choice = choices.find(o => o.value === 'high') || choices.find(o => o.value !== thought?.currentValue) || choices[0];
          if (choice) {
            thoughtId = thought.id; thoughtValue = choice.value;
            send(4, 'session/set_config_option', {sessionId, configId: thoughtId, value: thoughtValue});
          } else send(3, 'session/prompt', {sessionId, prompt: [{type: 'text', text: 'Reply OK'}]});
        }
        else { errorStage = 'session/new'; child.stdin.end(); }
      } else if (m.id === 4) {
        configApplied = (m.result?.configOptions || []).some(o => o.id === thoughtId && o.currentValue === thoughtValue);
        if (!configApplied) { errorStage = 'session/set_config_option'; child.stdin.end(); }
        else send(3, 'session/prompt', {sessionId, prompt: [{type: 'text', text: 'Reply OK'}]});
      } else if (m.id === 3 || m.id === 5) {
        declaredFailure = m.result?._meta?.jetbrains?.air?.sessionFailure?.severity === 'error';
        terminal = m.result?.stopReason === 'end_turn';
        turns.push({text: assistantText, stopReason: m.result?.stopReason});
        if (m.error) errorStage = 'session/prompt';
        if (scenario === 'conversation' && m.id === 3 && terminal && !errorStage) {
          assistantText = '';
          send(5, 'session/prompt', {sessionId, prompt: [{type: 'text', text: 'Continue this conversation. Reply FOLLOWUP_OK'}]});
        } else { child.stdin.end(); setTimeout(() => child.kill(), 200); }
      }
    }
  });
  const timer = setTimeout(() => { errorStage = 'timeout'; child.kill(); }, 45000);
  send(1, 'initialize', {protocolVersion: 1, clientInfo: {name: 'oob-adapter-fixture', version: '1'}, clientCapabilities: {_meta: config.clientCapabilityMeta || {}}});
  await new Promise(r => { child.once('close', r); child.once('error', e => { errorStage = e.code; r(); }); });
  clearTimeout(timer); server.closeAllConnections(); await new Promise(r => server.close(r));
  const expectedTexts = scenario === 'conversation' ? ['OK', 'FOLLOWUP_OK'] : ['OK'];
  const outputPassed = turns.length === expectedTexts.length && turns.every((t, i) =>
    t.text === expectedTexts[i] && t.stopReason === 'end_turn') && !foreignSessionOutput;
  const passed = initialized && sessionCreated && reached && !errorStage &&
    (scenario === 'failure' ? declaredFailure && !leakedFailureText && turns.length === 1 :
      outputPassed && (scenario !== 'conversation' || followupHasHistory));
  const result = {scenario, declaredFailure, leakedFailureText, assistantText, turns, foreignSessionOutput, followupHasHistory, case: filename.replace('.json', ''), initialized, sessionCreated, reasoningOptions, selectedThought: thoughtValue, configApplied, observedThought, reached, wirePath, requests, terminal, passed, errorStage};
  await writeFile(join(home, 'result.json'), JSON.stringify(result));
  await writeFile(join(home, 'stderr.log'), stderr, {mode: 0o600});
  console.log(JSON.stringify(result));
  if (!passed) process.exitCode = 1;
}
console.log(JSON.stringify({evidenceDirectory: root}));
