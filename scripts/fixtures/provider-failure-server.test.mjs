import test from 'node:test';
import assert from 'node:assert/strict';
import {once} from 'node:events';
import {createProviderFailureServer} from './provider-failure-server.mjs';

async function fixture(t) {
  const logs = [];
  const server = createProviderFailureServer({log: line => logs.push(line)});
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  assert.equal(server.address().address, '127.0.0.1');
  t.after(() => { server.closeAllConnections(); server.close(); });
  const base = `http://127.0.0.1:${server.address().port}`;
  const send = (marker, {suffix = '', signal} = {}) => fetch(`${base}/v1/chat/completions`, {
    method: 'POST', signal,
    headers: {'content-type': 'application/json', authorization: 'Bearer fixture-secret-must-not-be-reported'},
    body: JSON.stringify({model: 'gpt-4o', stream: true, messages: [
      {role: 'system', content: 'synthetic-private-context-must-not-be-reported'},
      {role: 'user', content: `Reply ${marker}${suffix}`},
    ]}),
  });
  const observe = async marker => {
    const response = await fetch(`${base}/__fixture/requests?marker=${encodeURIComponent(marker)}`);
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    return (await response.json()).requests[0];
  };
  return {base, logs, send, observe};
}

test('two temporary 503 responses recover on exactly the third identical request', async t => {
  const {send, observe} = await fixture(t);
  const marker = 'OOB_FAILURE_RECOVER_20261030_1';
  for (const expected of [503, 503, 200]) {
    const response = await send(marker);
    assert.equal(response.status, expected);
    const text = await response.text();
    if (expected === 200) {
      assert.match(response.headers.get('content-type'), /text\/event-stream/);
      assert.match(text, new RegExp(`${marker}_DONE`));
      assert.match(text, /data: \[DONE\]/);
    }
  }
  assert.deepEqual(await observe(marker), {
    marker, scenario: 'RECOVER', requestCount: 3, identicalRequestBodies: true,
    distinctRequestBodies: 1, responses: {'200': 1, '503': 2},
    completedResponses: 3, interruptedResponses: 0,
  });
});

test('persistent 503 observation counts attempts without making the fixture recover', async t => {
  const {send, observe} = await fixture(t);
  const marker = 'OOB_FAILURE_SERVER_20261030_2';
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const response = await send(marker);
    assert.equal(response.status, 503);
    await response.text();
  }
  assert.equal((await observe(marker)).requestCount, 3);
  assert.deepEqual((await observe(marker)).responses, {'503': 3});
});

test('backoff exposes a ten second Retry-After and records only requests actually received', async t => {
  const {send, observe} = await fixture(t);
  const marker = 'OOB_FAILURE_BACKOFF_20261030_9';
  const response = await send(marker);
  assert.equal(response.status, 503);
  assert.equal(response.headers.get('retry-after'), '10');
  assert.equal((await response.json()).error.code, 'server_error');
  assert.deepEqual(await observe(marker), {
    marker, scenario: 'BACKOFF', requestCount: 1, identicalRequestBodies: true,
    distinctRequestBodies: 1, responses: {'503': 1},
    completedResponses: 1, interruptedResponses: 0,
  });
});

test('quota is distinguishable from temporary rate limiting with Retry-After', async t => {
  const {send, observe} = await fixture(t);
  const quotaMarker = 'OOB_FAILURE_QUOTA_20261030_3';
  const quota = await send(quotaMarker);
  assert.equal(quota.status, 429);
  assert.equal(quota.headers.get('retry-after'), null);
  assert.equal((await quota.json()).error.code, 'insufficient_quota');
  assert.equal((await observe(quotaMarker)).requestCount, 1);

  const rateMarker = 'OOB_FAILURE_RETRYRATE_20261030_4';
  for (let attempt = 0; attempt < 3; attempt += 1) {
    const response = await send(rateMarker);
    if (attempt < 2) {
      assert.equal(response.status, 429);
      assert.equal(response.headers.get('retry-after'), '1');
      assert.equal((await response.json()).error.code, 'rate_limit_exceeded');
    } else {
      assert.equal(response.status, 200);
      assert.match(await response.text(), new RegExp(`${rateMarker}_DONE`));
    }
  }
  assert.equal((await observe(rateMarker)).requestCount, 3);
  assert.equal((await observe(rateMarker)).identicalRequestBodies, true);
});

test('partial output terminates without completing and remains a single observed request', async t => {
  const {send, observe} = await fixture(t);
  const marker = 'OOB_FAILURE_PARTIAL_20261030_5';
  const response = await send(marker);
  assert.equal(response.status, 200);
  const reader = response.body.getReader();
  assert.match(new TextDecoder().decode((await reader.read()).value), new RegExp(`${marker}_PARTIAL`));
  await assert.rejects(reader.read());
  const observation = await observe(marker);
  assert.equal(observation.requestCount, 1);
  assert.equal(observation.completedResponses, 0);
  assert.equal(observation.interruptedResponses, 1);
});

test('observations detect request mutation and never expose prompts credentials or hashes', async t => {
  const {base, send, observe, logs} = await fixture(t);
  const marker = 'OOB_FAILURE_RECOVER_20261030_6';
  await (await send(marker)).text();
  await (await send(marker, {suffix: ' changed'})).text();
  const observation = await observe(marker);
  assert.equal(observation.identicalRequestBodies, false);
  assert.equal(observation.distinctRequestBodies, 2);
  const all = await (await fetch(`${base}/__fixture/requests`)).text();
  const output = all + logs.join('\n');
  assert.doesNotMatch(output, /fixture-secret|synthetic-private-context|Reply |changed|Bearer|bodyHashes|sha256/i);
  assert.doesNotMatch(output, /[a-f0-9]{64}/);
  assert.equal((await fetch(`${base}/__fixture/requests?marker=private-content`)).status, 400);
  assert.equal((await fetch(`${base}/__fixture/requests`, {method: 'DELETE'})).status, 404);
});

test('request counters and recovery sequences are isolated by synthetic marker', async t => {
  const {send, observe} = await fixture(t);
  const first = 'OOB_FAILURE_RECOVER_20261030_7';
  const second = 'OOB_FAILURE_RECOVER_20261030_8';
  for (let attempt = 0; attempt < 3; attempt += 1) await (await send(first)).text();
  const response = await send(second);
  assert.equal(response.status, 503);
  await response.text();
  assert.equal((await observe(first)).requestCount, 3);
  assert.equal((await observe(second)).requestCount, 1);
});
