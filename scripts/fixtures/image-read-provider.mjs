// Local-only deterministic image-read provider for Android regression testing.
// OOB_IMAGE_TEST_FILE=/tmp/oob-large.png node scripts/fixtures/image-read-provider.mjs
// See docs/testing/image-read-crash-2026-09-07.md for the isolated AVD workflow.
import http from 'node:http';
import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';

const sourceFile = process.env.OOB_IMAGE_TEST_FILE;
if (!sourceFile) throw new Error('OOB_IMAGE_TEST_FILE must name the original large PNG');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const expectedHash = hash(readFileSync(sourceFile));
const port = Number(process.env.OOB_IMAGE_TEST_PORT || 18769);
let sequence = 0;

http.createServer(async (request, response) => {
  if (request.url === '/v1/models') {
    response.setHeader('content-type', 'application/json');
    response.end(JSON.stringify({data: [{id: 'gpt-4o', object: 'model', context_length: 128000}]}));
    return;
  }
  if (request.url !== '/v1/chat/completions') {
    response.writeHead(404).end();
    return;
  }
  try {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const raw = Buffer.concat(chunks);
    const messages = JSON.parse(raw).messages ?? [];
    const lastUserIndex = messages.findLastIndex(message => message.role === 'user');
    const userContent = messages[lastUserIndex]?.content;
    const marker = (typeof userContent === 'string' ? userContent : JSON.stringify(userContent))
      ?.match(/OOB_IMAGE_(SMALL|LARGE)/)?.[0];
    if (!marker) throw new Error('An explicit OOB_IMAGE_SMALL or OOB_IMAGE_LARGE test marker is required');
    const turnMessages = messages.slice(lastUserIndex + 1);
    const toolCount = turnMessages.filter(message => message.role === 'tool').length;
    const hashes = turnMessages.flatMap(message => Array.isArray(message.content) ? message.content : [])
      .map(part => part.image_url?.url)
      .filter(url => url?.startsWith('data:image/'))
      .map(url => hash(Buffer.from(url.slice(url.indexOf(',') + 1), 'base64')));
    if (marker.endsWith('LARGE') && toolCount > 0 && !hashes.includes(expectedHash)) {
      throw new Error('The model continuation did not receive the unchanged original image');
    }
    if (toolCount > 1) throw new Error('Unexpected duplicate tool result in one logical turn');
    const id = ++sequence;
    // Deliberately log only sizes, counters and hashes, never keys or user content.
    console.log(JSON.stringify({id, marker, requestBytes: raw.length, toolCount, hashes}));
    const done = toolCount === 1;
    const delta = done ? {role: 'assistant', content: `${marker}_DONE`} : {
      role: 'assistant',
      tool_calls: [{index: 0, id: `image_call_${id}`, type: 'function', function: {
        name: 'file_read',
        arguments: JSON.stringify({path: `/workspace/oob-image-repro/${marker.endsWith('SMALL') ? 'small.jpg' : 'large.png'}`}),
      }}],
    };
    response.writeHead(200, {'content-type': 'text/event-stream'});
    response.write(`data: ${JSON.stringify({id: `image_${id}`, choices: [{index: 0, delta, finish_reason: done ? 'stop' : 'tool_calls'}]})}\n\n`);
    response.end('data: [DONE]\n\n');
  } catch (error) {
    console.log(JSON.stringify({error: error.message}));
    if (!response.headersSent) response.writeHead(500);
    response.end();
  }
}).listen(port, '127.0.0.1', () => console.log(`Image fixture listening on ${port}`));
