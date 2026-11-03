// Real OpenCode Web HTTP smoke test using production-generated launch fixtures.
// No browser automation, personal credentials, or external model requests.
// Usage: node scripts/verify-opencode-web.mjs CLI_DIRECTORY FIXTURE_DIRECTORY
import assert from 'node:assert/strict';
import {spawn, execFileSync} from 'node:child_process';
import {once} from 'node:events';
import {createServer} from 'node:http';
import {mkdtemp, mkdir, readFile, readdir, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';
import {respondHarnessSuccess} from './harness-success-fixture.mjs';

const [cliDirectory, fixtureDirectory] = process.argv.slice(2);
assert(cliDirectory && fixtureDirectory, 'CLI and generated fixture directories are required');
const bin = resolve(cliDirectory, 'node_modules/.bin');
const version = execFileSync(join(bin, 'opencode'), ['--version'], {encoding: 'utf8'}).trim();
const cases = (await readdir(fixtureDirectory)).filter(name => name.startsWith('opencode-') && name.endsWith('.json'));
assert.equal(cases.length, 3, 'Run AgentWebRuntimeTest to generate all three Provider fixtures');
for (const filename of cases.sort()) {
  const fixture = JSON.parse(await readFile(join(fixtureDirectory, filename), 'utf8'));
  assert.equal(fixture.environment.OPENAI_API_KEY, 'fixture-key', 'Use test fixtures only');
  const root = await mkdtemp(join(tmpdir(), 'omnibot-opencode-web-'));
  const workspace = join(root, 'workspace');
  await mkdir(workspace);
  const expectedPath = fixture.protocol === 'anthropic' ? '/v1/messages'
    : fixture.wire === 'responses' ? '/v1/responses' : '/v1/chat/completions';
  const requests = [];
  const modelServer = createServer(async (req, res) => {
    if (req.url.endsWith('/models')) {
      res.writeHead(200, {'content-type': 'application/json'});
      res.end(JSON.stringify({data: [{id: 'org/test-model', object: 'model'}]}));
      return;
    }
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString());
    requests.push({path: req.url, model: body.model,
      credentialMatches: req.headers.authorization === 'Bearer fixture-key' || req.headers['x-api-key'] === 'fixture-key',
      headerMatches: req.headers['x-fixture'] === 'fixture-header'});
    respondHarnessSuccess(req, res, body, {reply: 'OPENCODE_WEB_FIXTURE_OK'});
  });
  await new Promise(resolve => modelServer.listen(0, '127.0.0.1', resolve));
  const providerBase = `http://127.0.0.1:${modelServer.address().port}`;
  const rewrite = text => String(text).replaceAll('https://fixture.invalid', providerBase).replaceAll('/root', root);
  let command = fixture.command;
  for (const content of Object.values(fixture.files)) {
    command = command.replace(Buffer.from(content).toString('base64'), Buffer.from(rewrite(content)).toString('base64'));
  }
  command = rewrite(command);
  const child = spawn('/bin/sh', ['-c', command], {
    cwd: workspace, detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: {PATH: `${bin}:${process.env.PATH}`, HOME: root, LANG: 'en_US.UTF-8', TERM: 'dumb',
      XDG_CONFIG_HOME: join(root, '.config'), XDG_DATA_HOME: join(root, '.data'),
      XDG_CACHE_HOME: join(root, '.cache'), XDG_STATE_HOME: join(root, '.state'),
      OPENCODE_DISABLE_MODELS_FETCH: 'true',
      ...Object.fromEntries(Object.entries(fixture.environment).map(([key, value]) => [key, rewrite(value)]))},
  });
  let transcript = '';
  let base;
  let result;
  const capture = chunk => { transcript = (transcript + chunk).slice(-65536); };
  child.stdout.on('data', capture);
  child.stderr.on('data', capture);
  try {
    let password;
    const startupDeadline = Date.now() + 60_000;
    while (Date.now() < startupDeadline) {
      const clean = transcript.replace(/\x1b\[[0-?]*[ -/]*[@-~]/g, '');
      base = clean.match(/^server listening on (http:\/\/127\.0\.0\.1:\d+)\s*$/m)?.[1];
      password = clean.match(/^server password ([A-Za-z0-9_-]{16,256})\s*$/m)?.[1];
      if (base && password) break;
      assert.equal(child.exitCode, null, 'OpenCode exited before Web startup');
      await delay(100);
    }
    assert(base && password, 'OpenCode did not publish its foreground endpoint and credentials');
    const auth = `Basic ${Buffer.from(`opencode:${password}`).toString('base64')}`;
    const request = (path, options = {}) => fetch(`${base}${path}`, {signal: AbortSignal.timeout(15_000), ...options});
    assert.equal((await request('/api/pair', {method: 'POST'})).status, 401, 'Pairing requires authentication');
    const pair = async () => {
      const response = await request('/api/pair', {method: 'POST', headers: {Authorization: auth, Accept: 'application/json'}});
      assert.equal(response.status, 200);
      const body = await response.json();
      assert.match(body.code, /^[A-Za-z0-9_-]{16,128}$/);
      assert(body.expires_in > 0);
      return body.code;
    };
    const code = await pair();
    const connected = await request(`/auth/connect/${code}`, {redirect: 'manual', headers: {Accept: 'text/html'}});
    assert.equal(connected.status, 302);
    assert.equal(connected.headers.get('location'), '/');
    const cookies = connected.headers.getSetCookie();
    assert(cookies.some(value => /httponly/i.test(value)));
    const cookie = cookies.map(value => value.split(';')[0]).join('; ');
    assert.equal((await request(`/auth/connect/${code}`, {headers: {Accept: 'text/html'}, redirect: 'manual'})).status, 401);
    assert.notEqual(await pair(), code, 'Reopening must mint a fresh single-use code');
    const info = await request('/api/info', {headers: {Cookie: cookie}});
    assert.equal(info.status, 200);
    const serverInfo = await info.json();
    assert(serverInfo.version);
    const page = await request('/', {headers: {Cookie: cookie}});
    assert.equal(page.status, 200);
    assert.match(page.headers.get('content-type'), /text\/html/);
    const html = await page.text();
    const asset = html.match(/<script[^>]+src="([^"]+)"/)?.[1];
    assert(asset, 'The official HTML shell must reference its JavaScript bundle');
    const assetUrl = new URL(asset, base);
    assert.equal(assetUrl.origin, base);
    const bundle = await request(assetUrl.pathname, {headers: {Cookie: cookie}});
    assert.equal(bundle.status, 200);
    assert.match(bundle.headers.get('content-type'), /javascript/);
    const json = async (path, body) => {
      const response = await request(path, {method: body ? 'POST' : 'GET',
        headers: {Cookie: cookie, 'Content-Type': 'application/json'}, body: body ? JSON.stringify(body) : undefined});
      assert.equal(response.status, 200, `${path} must succeed`);
      return response.json();
    };
    const session = await json('/api/session', {location: {directory: workspace}, title: 'OpenCode Web fixture'});
    const sessionId = session.data.id;
    assert(sessionId);
    await json(`/api/session/${sessionId}/prompt`, {text: 'Say OPENCODE_WEB_FIXTURE_OK without using tools.'});
    let replied = false;
    const replyDeadline = Date.now() + 30_000;
    while (Date.now() < replyDeadline) {
      const messages = await json(`/api/session/${sessionId}/message?type=assistant`);
      if (JSON.stringify(messages).includes('OPENCODE_WEB_FIXTURE_OK')) { replied = true; break; }
      await delay(200);
    }
    assert(replied, 'The configured Provider must produce an actual Web session reply');
    assert(requests.length > 0);
    assert(requests.every(r => r.path === expectedPath && r.model === 'org/test-model' && r.credentialMatches && r.headerMatches),
      'Every model request must use the selected Provider, model and headers');
    result = {case: filename, version, paired: true, singleUse: true, cookieAuthenticated: true,
      officialWebAssets: true, wirePath: expectedPath, providerRequests: requests.length, replied: true, passed: true};
  } finally {
    try { process.kill(-child.pid, 'SIGTERM'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
    if (child.exitCode === null && child.signalCode === null) {
      await Promise.race([once(child, 'exit'), delay(3000)]);
      if (child.exitCode === null && child.signalCode === null) process.kill(-child.pid, 'SIGKILL');
    }
    await new Promise(resolve => modelServer.close(resolve));
    await rm(root, {recursive: true, force: true});
    if (base) await assert.rejects(fetch(`${base}/api/info`, {signal: AbortSignal.timeout(2000)}), 'Stopping the foreground process must close its Web listener');
  }
  console.log(JSON.stringify({...result, stopped: true}));
}
