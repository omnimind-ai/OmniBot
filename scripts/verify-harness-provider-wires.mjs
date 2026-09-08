// Exercise the official SDK drivers used by the OpenCode config adapter.
// Install ai, @ai-sdk/openai, @ai-sdk/openai-compatible and @ai-sdk/anthropic
// in a temporary directory, then pass that directory. No external requests or keys.
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
const directory = process.argv[2];
assert(directory, 'Usage: node scripts/verify-harness-provider-wires.mjs SDK_DIRECTORY');
const require = createRequire(resolve(directory, 'package.json'));
const load = name => import(pathToFileURL(require.resolve(name)).href);
const {generateText} = await load('ai');
const {createOpenAI} = await load('@ai-sdk/openai');
const {createOpenAICompatible} = await load('@ai-sdk/openai-compatible');
const {createAnthropic} = await load('@ai-sdk/anthropic');
for (const [wire, create, path, bodyKey] of [
  ['chat_completions', options => createOpenAICompatible({...options, name: 'omnibot'}).chatModel('org/model-a'), '/v1/chat/completions', 'messages'],
  ['responses', options => createOpenAI(options).responses('org/model-a'), '/v1/responses', 'input'],
  ['anthropic', options => createAnthropic(options)('org/model-a'), '/v1/messages', 'messages'],
]) {
  const requests = [];
  const model = create({
    baseURL: 'https://fixture.invalid/v1', apiKey: 'local-fixture-key',
    fetch: async (url, init) => {
      requests.push({url: String(url), body: JSON.parse(init.body)});
      return new Response(JSON.stringify({error: {type: 'authentication_error', message: 'fixture intentional rejection'}}),
        {status: 401, headers: {'content-type': 'application/json'}});
    },
  });
  await assert.rejects(generateText({model, prompt: 'wire fixture', maxRetries: 0}));
  assert.equal(requests.length, 1, `${wire} must not retry`);
  assert.equal(new URL(requests[0].url).pathname, path);
  assert.equal(requests[0].body.model, 'org/model-a');
  assert(Array.isArray(requests[0].body[bodyKey]));
  console.log(JSON.stringify({wire, path, modelPreserved: true, requests: 1, passed: true}));
}
