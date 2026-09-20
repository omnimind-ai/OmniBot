import test from 'node:test';
import assert from 'node:assert/strict';
import { checkProvider } from '../app/src/main/assets/builtin_skills/self-improving-agent/scripts/check-provider.mjs';
import { mkdtemp, cp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';
import http from 'node:http';

const config = { endpoint: 'https://example.invalid/v1/chat/completions', model: 'GLM-5.1', apiKey: 'private-token' };
test('probe does not leak secrets, retry a rejection or follow redirects', async () => {
  for (const status of [400, 401, 429, 503]) {
    let calls = 0;
    const result = await checkProvider(config, async (url, options) => {
      calls++;
      assert.equal(options.redirect, 'error');
      assert.equal(JSON.parse(options.body).model, 'GLM-5.1');
      return new Response('private-token user-message', { status });
    });
    assert.equal(calls, 1);
    assert.equal(result.status, 'needs_verification');
    assert.equal(result.httpStatus, status);
    assert.ok(!JSON.stringify(result).includes('private'));
  }
});
test('reasoning-only and malformed completions are not a verified repair', async () => {
  for (const body of ['invalid-json', '{"choices":[{"message":{"reasoning_content":"thinking"}}]}']) {
    const result = await checkProvider(config, async () => new Response(body));
    assert.equal(result.status, 'needs_verification');
  }
  const result = await checkProvider(config, async () => { throw new Error('private-token'); });
  assert.equal(result.failureKind, 'transport_or_invalid_response');
});
test('CLI accepts stdin and persists safe results across separate invocations', async () => {
  const root = await mkdtemp(join(tmpdir(), 'provider-recovery-'));
  const server = http.createServer((req, res) => {
    res.setHeader('content-type', 'application/json');
    res.end(JSON.stringify({ choices: [{ message: { content: 'PROVIDER_CHECK_OK' } }] }));
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    await cp(new URL('../app/src/main/assets/builtin_skills/self-improving-agent/', import.meta.url), root, { recursive: true });
    for (let i = 0; i < 2; i++) {
      const child = spawn(process.execPath, [join(root, 'scripts/check-provider.mjs')]);
      let output = '';
      child.stdout.on('data', chunk => output += chunk);
      child.stdin.end(JSON.stringify({ ...config, endpoint: `http://127.0.0.1:${server.address().port}/v1/chat/completions` }));
      assert.equal(await new Promise(resolve => child.on('close', resolve)), 0);
      assert.equal(JSON.parse(output).status, 'text_probe_passed');
      const saved = await readFile(join(root, 'data/provider-check.json'), 'utf8');
      assert.equal(JSON.parse(saved).status, 'text_probe_passed');
      assert.ok(!saved.includes('private-token'));
      assert.ok(!saved.includes('GLM-5.1'));
    }
    const invalid = spawn(process.execPath, [join(root, 'scripts/check-provider.mjs')]);
    invalid.stdin.end(JSON.stringify({ ...config, model: '' }));
    invalid.stdout.resume();
    assert.equal(await new Promise(resolve => invalid.on('close', resolve)), 1);
    const failed = JSON.parse(await readFile(join(root, 'data/provider-check.json'), 'utf8'));
    assert.equal(failed.status, 'check_failed');
    assert.equal(failed.failureKind, 'configuration');
  } finally { server.closeAllConnections(); server.close(); await rm(root, { recursive: true, force: true }); }
});
test('vision probe validates image bytes and actual native tool response', async () => {
  const { inflateSync } = await import('node:zlib');
  const result = await checkProvider({ ...config, probe: 'vision_tool' }, async (_, options) => {
    const request = JSON.parse(options.body);
    assert.equal(request.tools[0].function.name, 'report_color');
    const image = Buffer.from(request.messages[0].content[1].image_url.url.split(',')[1], 'base64');
    assert.equal(image.readUInt32BE(16), 32);
    assert.equal(image.readUInt32BE(20), 32);
    let offset = 8; const chunks = [];
    while (offset < image.length) {
      const size = image.readUInt32BE(offset);
      if (image.toString('ascii', offset + 4, offset + 8) === 'IDAT') chunks.push(image.subarray(offset + 8, offset + 8 + size));
      offset += size + 12;
    }
    const pixels = inflateSync(Buffer.concat(chunks));
    assert.equal(pixels.length, 32 * 97);
    for (let row = 0; row < 32; row++) {
      assert.equal(pixels[row * 97], 0);
      assert.ok(pixels.subarray(row * 97 + 1, (row + 1) * 97).every(x => x === 255));
    }
    return new Response(JSON.stringify({ choices: [{ message: { tool_calls: [{ function: { name: 'report_color', arguments: '{"color":"white"}' } }] } }] }));
  });
  assert.equal(result.status, 'vision_tool_probe_passed');
  const textOnly = await checkProvider({ ...config, probe: 'vision_tool' }, async () =>
    new Response('{"choices":[{"message":{"content":"white"}}]}'));
  assert.equal(textOnly.status, 'needs_verification');
});

test('probe uses configured header authentication and permits anonymous providers', async () => {
  for (const entry of [
    { apiKey: 'generated', headers: { authorization: 'Bearer configured' }, expected: 'Bearer configured' },
    { apiKey: '', headers: { 'x-api-key': 'configured' }, expected: null },
    { apiKey: '', expected: null },
  ]) {
    const result = await checkProvider({ ...config, ...entry }, async (_, options) => {
      assert.equal(options.headers.get('authorization'), entry.expected);
      if (entry.headers?.['x-api-key']) assert.equal(options.headers.get('x-api-key'), 'configured');
      return new Response('{"choices":[{"message":{"content":"PROVIDER_CHECK_OK"}}]}');
    });
    assert.equal(result.status, 'text_probe_passed');
  }
  let calls = 0;
  await assert.rejects(checkProvider({ ...config, headers: { AUTHORIZATION: ' ' } }, async () => { calls++; }));
  assert.equal(calls, 0);
});
