// Real official ACP processes -> transparent loopback observer -> configured API.
// No synthetic model responses, route fallback, or automatic prompt retry.
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createInterface} from 'node:readline';
import {mkdtemp, mkdir, readFile, writeFile, rm} from 'node:fs/promises';
import {join, resolve, dirname} from 'node:path';
import {tmpdir} from 'node:os';
import {once} from 'node:events';
import {randomUUID} from 'node:crypto';
import {createProviderObserver} from './agent-provider-observer.mjs';
import {providerSmokeConfigFromEnvironment, providerModelsUrl, extractModelIds} from './agent_provider_smoke.mjs';

const [cliDirectory, fixtures, filter = 'codex,claude-code,kimi-code,open-code,deepseek-harness'] = process.argv.slice(2);
assert(cliDirectory && fixtures, 'Usage: verify-live-harness-conversations.mjs CLI_DIRECTORY FIXTURES [HARNESS_IDS]');
const {apiKey, baseUrl, model, timeoutMs} = providerSmokeConfigFromEnvironment();
const secondModel = process.env.OMNIBOT_TEST_SECOND_MODEL;
assert(apiKey && secondModel && secondModel !== model, 'Configure API key and two distinct test models');
const upstream = new URL(baseUrl);
assert(['', '/', '/v1', '/v1/'].includes(upstream.pathname), 'Set a Provider origin or /v1 endpoint');
const catalogResponse = await fetch(providerModelsUrl(baseUrl), {headers: {Authorization: `Bearer ${apiKey}`}, signal: AbortSignal.timeout(timeoutMs)});
assert(catalogResponse.ok, `Model discovery HTTP ${catalogResponse.status}`);
const ids = extractModelIds(await catalogResponse.json());
assert(ids.includes(model) && ids.includes(secondModel), 'Both selected models must exist in the actual Provider catalog');
const evidence = await mkdtemp(join(tmpdir(), 'oob-live-harness-evidence-'));
const reports = [];
const definitions = {
  codex: ['codex-openai_compatible-responses.json', 'codex-acp', []],
  'claude-code': ['claude-code-anthropic-chat_completions.json', 'claude-agent-acp', []],
  'kimi-code': ['kimi-code-openai_compatible-chat_completions.json', 'kimi', ['acp']],
  'open-code': ['open-code-openai_compatible-chat_completions.json', 'opencode', ['acp']],
  'deepseek-harness': ['deepseek-harness-openai_compatible-chat_completions.json', 'dsh', ['--profile', 'acp']],
};

for (const harness of filter.split(',')) {
  assert(definitions[harness], 'Unknown Harness filter');
  const report = {harness, passed: false, cases: [], requests: []};
  const home = await mkdtemp(join(tmpdir(), 'oob-live-harness-'));
  const workspace = join(home, 'workspace'); await mkdir(workspace);
  const observer = createProviderObserver(upstream.origin, event => report.requests.push(event));
  observer.listen(0, '127.0.0.1'); await once(observer, 'listening');
  const origin = `http://127.0.0.1:${observer.address().port}`;
  let client;
  const check = async (name, task) => {
    try { const detail = await task(); report.cases.push({name, passed: true, ...detail}); }
    catch (error) {
      // Error payloads from official CLIs may contain credentials or conversation text.
      report.cases.push({name, passed: false, errorType: error.name,
        method: error.method, rpcCode: error.rpcCode,
        assertionSite: error.stack?.split('\n').find(line => /^\s+at /.test(line) && line.includes('verify-live-harness-conversations.mjs'))?.trim()});
      if (error.name !== 'AssertionError') throw error;
    }
  };
  try {
    const [fixtureName, command, args] = definitions[harness];
    const fixture = JSON.parse(await readFile(join(fixtures, fixtureName), 'utf8'));
    const rewrite = value => String(value).replaceAll('https://fixture.invalid', origin)
      .replaceAll('/root', home).replaceAll('org/test-model', model)
      .replaceAll('org/second-model', secondModel).replaceAll('fixture-key', apiKey);
    // Prevent config-string injection into generated JSON/TOML/YAML fixtures.
    assert(!/[\r\n"\\]/.test(apiKey + model + secondModel), 'Unsupported characters in fixture substitutions');
    const env = Object.fromEntries(Object.entries(process.env).filter(([k]) =>
      !/^(OPENAI|ANTHROPIC|CLAUDE|CODEX|KIMI|DEEPSEEK|DSH|OPENCODE)|TOKEN|SECRET|PASSWORD|API_KEY/.test(k)));
    Object.assign(env, {HOME: home, XDG_CONFIG_HOME: join(home, '.config'), CLAUDE_CONFIG_DIR: join(home, '.claude'),
      PATH: resolve(cliDirectory, 'node_modules/.bin') + ':' + env.PATH, OPENCODE_DISABLE_MODELS_FETCH: 'true'},
      Object.fromEntries(Object.entries(fixture.environment).map(([k, v]) => [k, rewrite(v)])));
    for (const file of fixture.files) {
      const path = rewrite(file.path); await mkdir(dirname(path), {recursive: true});
      await writeFile(path, rewrite(file.content), {mode: 0o600});
    }
    if (harness === 'open-code') env.OPENCODE_CONFIG = rewrite(fixture.files[0].path);
    const launchArgs = harness === 'deepseek-harness' ? [...args, '--patch', rewrite(fixture.files[0].path)] : args;
    function start() {
      const child = spawn(resolve(cliDirectory, 'node_modules/.bin', command), launchArgs,
        {cwd: workspace, env, stdio: ['pipe', 'pipe', 'pipe'], detached: true});
      const pending = new Map(); let seq = 0, active = null;
      child.stderr.resume(); // Never print SDK diagnostics containing secrets.
      child.stdin.on('error', () => {});
      const send = message => child.stdin.write(JSON.stringify({jsonrpc: '2.0', ...message}) + '\n');
      const fail = () => { for (const p of pending.values()) p.reject(Error('ACP process exited')); pending.clear(); };
      child.on('error', fail); child.on('exit', fail);
      createInterface({input: child.stdout}).on('line', line => {
        let m; try { m = JSON.parse(line); } catch { return; }
        if (m.method === 'session/update' && active) {
          if (m.params.sessionId !== active.sessionId) { active.foreignSession = true; return; }
          const u = m.params.update;
          if (u?.sessionUpdate === 'agent_message_chunk' && u.content?.type === 'text') active.text += u.content.text;
          if (u?.sessionUpdate === 'tool_call' || u?.sessionUpdate === 'tool_call_update') {
            active.toolUpdates++;
            active.rawOutputMarker ||= JSON.stringify(u.rawOutput || '').includes('TOOL_STDOUT_OK');
            for (const c of u.content || []) if (c.type === 'content' && c.content?.type === 'text') active.toolText += c.content.text;
          }
        }
        if (m.method && m.id !== undefined) {
          const option = m.method === 'session/request_permission' && m.params.options?.find(o => o.kind === 'allow_once');
          send(option ? {id: m.id, result: {outcome: {outcome: 'selected', optionId: option.optionId}}} :
            {id: m.id, error: {code: -32601, message: 'Unsupported test client capability'}});
        } else if (pending.has(m.id)) {
          const p = pending.get(m.id); pending.delete(m.id);
          if (m.error) p.reject(Object.assign(Error('ACP request failed'), {method: p.method, rpcCode: m.error.code})); else p.resolve(m.result);
        }
      });
      async function call(method, params) {
        const id = ++seq;
        return new Promise((resolveCall, reject) => {
          const timer = setTimeout(() => {pending.delete(id); reject(Object.assign(Error('ACP request timed out'), {method}));}, timeoutMs);
          pending.set(id, {method, resolve: value => {clearTimeout(timer); resolveCall(value);}, reject: e => {clearTimeout(timer); reject(e);}});
          send({id, method, params});
        });
      }
      return {call,
        async prompt(sessionId, text, cancel = false) {
          assert(!active); active = {sessionId, text: '', toolText: '', toolUpdates: 0, rawOutputMarker: false, foreignSession: false};
          let cancelTimer;
          try {
            const responsePromise = call('session/prompt', {sessionId, prompt: [{type: 'text', text}]});
            if (cancel) cancelTimer = setTimeout(() => send({method: 'session/cancel', params: {sessionId}}), 1000);
            const response = await responsePromise;
            assert(!active.foreignSession, 'Output belongs to a different session');
            assert(!response?._meta?.jetbrains?.air?.sessionFailure, 'Adapter declared a request failure');
            assert.equal(response.stopReason, cancel ? 'cancelled' : 'end_turn');
            if (!cancel) assert(active.text.trim(), 'No visible assistant answer');
            return {...active};
          } finally {clearTimeout(cancelTimer); active = null;}
        },
        async stop() {
          if (!child.pid || child.exitCode !== null || child.signalCode !== null) return;
          const done = once(child, 'exit');
          try {process.kill(-child.pid, 'SIGTERM');} catch {}
          const killTimer = setTimeout(() => {try {process.kill(-child.pid, 'SIGKILL');} catch {}}, 2000);
          await done; clearTimeout(killTimer);
        },
      };
    }
    client = start();
    const initialize = () => client.call('initialize', {protocolVersion: 1,
      clientInfo: {name: 'oob-live-regression', version: '1'}, clientCapabilities: {_meta: fixture.clientCapabilityMeta || {}}});
    const initialized = await initialize();
    let session = await client.call('session/new', {cwd: workspace, mcpServers: []});
    const sessionId = session.sessionId; assert(sessionId);
    const token = 'oob-' + randomUUID();
    await check('visible_answer', async () => {
      const answer = await client.prompt(sessionId, `Remember this test token: ${token}. Reply exactly READY. Do not use tools.`);
      assert.equal(answer.text.trim(), 'READY');
    });
    await check('same_session_context', async () => {
      const answer = await client.prompt(sessionId, 'Reply with only the test token I gave you. Do not use tools.');
      assert.equal(answer.text.trim(), token);
    });
    await check('real_tool_and_output', async () => {
      const answer = await client.prompt(sessionId,
        `Use a terminal tool to run python3 that writes the exact text TOOL_FILE_OK to ${join(workspace, 'result.txt')} and prints TOOL_STDOUT_OK. Do not modify other files. Then reply TOOL_DONE.`);
      const fileText = await readFile(join(workspace, 'result.txt'), 'utf8').catch(error => {
        if (error.code === 'ENOENT') return null;
        throw error;
      });
      report.toolEvidence = {fileVerified: true, updates: answer.toolUpdates,
        standardOutputChars: answer.toolText.length, markerVisible: answer.toolText.includes('TOOL_STDOUT_OK'),
        rawOutputMarker: answer.rawOutputMarker};
      report.toolEvidence.fileVerified = fileText === 'TOOL_FILE_OK';
      assert.equal(fileText, 'TOOL_FILE_OK');
      assert(answer.toolUpdates > 0 &&
        (answer.toolText.includes('TOOL_STDOUT_OK') || answer.rawOutputMarker),
        'Tool output absent from official ACP content and rawOutput');
    });
    const configOptions = result => result.configOptions || [];
    const choices = option => (option?.options || []).flatMap(o => o.options || [o]);
    await check('model_switch_actual_request', async () => {
      const option = configOptions(session).find(o => o.category === 'model'); assert(option);
      const target = choices(option).find(o => o.value === secondModel || o.value === `omnibot/${secondModel}` ||
        (() => {try {return JSON.parse(o.value).at(-1) === secondModel;} catch {return false;}})());
      assert(target, 'Second Provider model absent from official options');
      session = await client.call('session/set_config_option', {sessionId, configId: option.id, value: target.value});
      assert.equal(configOptions(session).find(o => o.id === option.id)?.currentValue, target.value);
      const offset = report.requests.length;
      const answer = await client.prompt(sessionId, 'Reply MODEL_SWITCH_OK only. Do not use tools.');
      assert.equal(answer.text.trim(), 'MODEL_SWITCH_OK');
      assert(report.requests.slice(offset).some(r => r.phase === 'request' && r.model === secondModel));
    });
    await check('official_reasoning_setting', async () => {
      const option = configOptions(session).find(o => o.category === 'thought_level');
      if (!option) return {supported: false, reason: 'Official session declares no reasoning option'};
      const target = choices(option).find(o => o.value !== option.currentValue); assert(target);
      const reasoningWire = request => JSON.stringify([request?.effort ?? null,
        request?.thinkingType ?? null, request?.thinkingBudget ?? null]);
      const before = report.requests.findLast(r => r.phase === 'request' && r.model === secondModel);
      const configured = await client.call('session/set_config_option', {sessionId, configId: option.id, value: target.value});
      assert.equal(configOptions(configured).find(o => o.id === option.id)?.currentValue, target.value);
      const offset = report.requests.length;
      const answer = await client.prompt(sessionId, 'Reply REASONING_SETTING_OK only. Do not use tools.');
      assert.equal(answer.text.trim(), 'REASONING_SETTING_OK');
      const after = report.requests.slice(offset).filter(r => r.phase === 'request' && r.model === secondModel);
      // ACP option values are Harness-owned. For example, disabling thinking
      // can remove reasoning_effort rather than send the string "off".
      assert(before && after.length && after.some(r => reasoningWire(r) !== reasoningWire(before)),
        'Official setting changed but actual reasoning request configuration did not');
      return {supported: true, selected: target.value, before: reasoningWire(before), after: reasoningWire(after.at(-1))};
    });
    await check('process_restart_session_restore', async () => {
      if (!initialized.agentCapabilities?.loadSession) return {supported: false, reason: 'Official ACP session/load is unsupported'};
      await client.stop(); client = start(); await initialize();
      await client.call('session/load', {sessionId, cwd: workspace, mcpServers: []});
      const answer = await client.prompt(sessionId, 'Reply with only the original test token. Do not use tools.');
      assert.equal(answer.text.trim(), token);
      return {supported: true};
    });
    await check('official_cancel_and_next_turn', async () => {
      await client.prompt(sessionId, 'Use a terminal tool to sleep for 30 seconds, then reply WAIT_DONE.', true);
      const answer = await client.prompt(sessionId, 'Reply CANCEL_RECOVERED only. Do not use tools.');
      assert.equal(answer.text.trim(), 'CANCEL_RECOVERED');
    });
    report.passed = report.cases.every(c => c.passed);
    if (!report.passed) process.exitCode = 1;
  } catch (error) {
    if (!report.cases.some(c => !c.passed)) report.cases.push({name: 'preparation', passed: false,
      errorType: error.name, method: error.method, rpcCode: error.rpcCode});
    process.exitCode = 1;
  } finally {
    const unexpectedModels = [...new Set(report.requests.filter(r => r.phase === 'request' && r.model && !ids.includes(r.model)).map(r => r.model))];
    report.cases.push({name: 'requested_models_in_provider_catalog', passed: unexpectedModels.length === 0, unexpectedModels});
    if (unexpectedModels.length) { report.passed = false; process.exitCode = 1; }
    for (const name of ['visible_answer', 'same_session_context', 'real_tool_and_output',
      'model_switch_actual_request', 'official_reasoning_setting', 'process_restart_session_restore',
      'official_cancel_and_next_turn']) {
      if (!report.cases.some(c => c.name === name)) report.cases.push({name, passed: false, notRun: true});
    }
    await client?.stop(); observer.closeAllConnections(); observer.close();
    await rm(home, {recursive: true, force: true}); // Only this run's private test workspace and credentials.
    reports.push(report);
    await writeFile(join(evidence, `${harness}.json`), JSON.stringify(report, null, 2));
    console.log(JSON.stringify(report));
  }
}
await writeFile(join(evidence, 'summary.json'), JSON.stringify({passed: reports.every(r => r.passed), reports}, null, 2));
console.log(JSON.stringify({evidenceDirectory: evidence}));
