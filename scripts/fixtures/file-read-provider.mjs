// Local deterministic provider; see docs/testing/file-read-memory-2026-09-07.md.
// OOB_FILE_TEST_DIR=/tmp/oob-file-repro node scripts/fixtures/file-read-provider.mjs
import http from 'node:http';
import {respondXiaowanSchedule} from './xiaowan-schedule-scenarios.mjs';
import {respondXiaowanSession} from './xiaowan-session-scenarios.mjs';
import {respondXiaowanFailure} from './xiaowan-failure-scenarios.mjs';
import {createHash} from 'node:crypto';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {join} from 'node:path';
import {execFileSync} from 'node:child_process';

const directory = process.env.OOB_FILE_TEST_DIR;
if (!directory) throw Error('OOB_FILE_TEST_DIR is required');
const html = readFileSync(join(directory, 'large.html'), 'utf8');
const names = {HTML: 'large.html', TEXT: 'notes.txt', PDF: 'sample.pdf', CANCEL: 'large.html', HOLD: 'large.html', CONTEXT: 'large.html'};
const imagePath = process.env.OOB_FILE_TEST_IMAGE;
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const imageHash = imagePath ? hash(readFileSync(imagePath)) : null;
const held = new Map();
const offloadCache = new Map();
function restoredToolOutput(text) {
  const match = /^Earlier tool output saved in full to (.+)\. Read it with file_read if needed\.$/.exec(text);
  if (!match) return text;
  const path = match[1];
  assert(/^\/workspace\/[A-Za-z0-9_/.-]+$/.test(path) && !path.split('/').includes('..'));
  const serial = process.env.OOB_FILE_TEST_SERIAL;
  assert(serial, 'OOB_FILE_TEST_SERIAL is required to verify offloaded results on the actual device');
  if (!offloadCache.has(path)) offloadCache.set(path, execFileSync(process.env.ADB || 'adb',
    ['-s', serial, 'exec-out', 'run-as', 'cn.com.omnimind.bot', 'cat', path.slice(1)],
    {encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, timeout: 15000}));
  return offloadCache.get(path);
}
const sse = (delta, finish = null) => `data: ${JSON.stringify({choices: [{index: 0, delta, finish_reason: finish}]})}\n\n`;
let sequence = 0;

http.createServer(async (request, response) => {
  if (request.method === 'POST' && request.url?.startsWith('/fixture/release?')) {
    const marker = new URL(request.url, 'http://127.0.0.1').searchParams.get('marker');
    const release = held.get(marker);
    if (!release) return response.writeHead(404).end();
    release();
    return response.writeHead(200).end('released');
  }
  if (request.url === '/v1/models') {
    response.setHeader('content-type', 'application/json');
    response.end(JSON.stringify({data: [{id: 'gpt-4o', object: 'model', context_length: 128000}]}));
    return;
  }
  if (request.url !== '/v1/chat/completions') return response.writeHead(404).end();
  try {
    const chunks = [];
    for await (const chunk of request) chunks.push(chunk);
    const raw = Buffer.concat(chunks);
    const body = JSON.parse(raw);
    if (respondXiaowanFailure(request, response, body)) return;
    if (respondXiaowanSession(response, body)) return;
    if (respondXiaowanSchedule(response, body)) return;
    const messages = body.messages;
    const textContent = message => typeof message?.content === 'string' ? message.content
      : (message?.content || []).filter(part => part.type === 'text').map(part => part.text).join('\n');
    const checkpointCount = marker => Math.max(0, ...messages.map(message => {
      const found = textContent(message).match(/File checkpoint for (OOB_FILE_[A-Z0-9_]+); completedReads=(\d+)/);
      return found?.[1] === marker ? Number(found[2]) : 0;
    }));
    if (textContent(messages.at(-1)) === 'Generate the replacement context summary now.') {
      const prefix = messages.slice(0, -1);
      const userIndex = prefix.findLastIndex(message => message.role === 'user');
      const marker = textContent(prefix[userIndex]).match(/OOB_FILE_CONTEXT(?:_[A-Z0-9]+)*/)?.[0]
        || JSON.stringify(prefix).match(/File checkpoint for (OOB_FILE_CONTEXT[A-Z0-9_]+);/)?.[1];
      assert(marker, 'Summary fixture requires an identifiable file task');
      assert(JSON.stringify(messages).length <= 1048566, 'Summary request exceeds the same provider limit');
      const completed = checkpointCount(marker) + prefix.slice(userIndex + 1).filter(message => message.role === 'tool').length;
      console.log(JSON.stringify({marker, summary: true, completedReads: completed, requestChars: JSON.stringify(messages).length}));
      response.writeHead(200, {'content-type': 'text/event-stream'});
      response.end(sse({content: `File checkpoint for ${marker}; completedReads=${completed}. Continue from the next page without repeating completed reads.`}, 'stop') + 'data: [DONE]\n\n');
      return;
    }
    const lastUser = messages.findLastIndex(message => message.role === 'user');
    const memoryMarker = textContent(messages[lastUser]).match(/OOB_MEMORY_(WRITE|RECALL)_(\d+)/);
    if (memoryMarker) {
      const marker = memoryMarker[0], write = memoryMarker[1] === 'WRITE';
      const anchor = `OOB_MEMORY_${memoryMarker[2]}`;
      const longText = `${anchor} long-term preference: test color is cerulean.`;
      const dailyText = `${anchor} daily task: check the test triangle.`;
      const system = messages.filter(m => m.role === 'system').map(textContent).join('\n');
      assert(system.includes('Before answering about prior work, decisions, preferences, or pending tasks'));
      assert(!system.includes('Use a listed memory capability only when'));
      const tools = JSON.parse(raw).tools.map(t => t.function.name);
      for (const name of ['memory_search', 'memory_load', 'memory_upsert_longterm', 'memory_write_daily']) assert(tools.includes(name), `Missing ${name}`);
      const results = messages.slice(lastUser + 1).filter(m => m.role === 'tool').map(m => {
        const outer = JSON.parse(textContent(m));
        assert.equal(outer.success, true, 'Memory tool failed');
        return JSON.parse(outer.rawResultJson);
      });
      let name, args;
      const offset = write ? 2 : 0;
      if (write && results.length === 0) { name = 'memory_upsert_longterm'; args = {text:longText}; }
      else if (write && results.length === 1) { assert(results[0].inserted); name = 'memory_write_daily'; args = {text:dailyText}; }
      else if (results.length === offset) { name = 'memory_search'; args = {query:anchor,limit:20}; }
      else {
        const search = results[offset];
        const long = search.hits.find(h => h.text.includes(longText));
        const daily = search.hits.find(h => h.text.includes(dailyText));
        assert(long?.slug && daily, 'Both long-term and daily memory must be retrieved');
        if (results.length === offset + 1) { name='memory_load'; args={slug:long.slug}; }
        else { assert(results[offset+1].body.includes(longText), 'Loaded body must match stored long-term memory'); }
      }
      console.log(JSON.stringify({marker,memory:true,step:results.length,nextTool:name||null,verified:!name}));
      response.writeHead(200, {'content-type':'text/event-stream'});
      response.end(sse(name ? {tool_calls:[{index:0,id:`${marker}_${results.length}`,type:'function',function:{name,arguments:JSON.stringify(args)}}]} : {content:`${marker}_DONE`},name?'tool_calls':'stop')+'data: [DONE]\n\n');
      return;
    }
    const match = JSON.stringify(messages[lastUser]?.content)?.match(/OOB_FILE_(HTML|TEXT|PDF|IMAGE|CANCEL|HOLD|CONTEXT)(?:_[A-Z0-9]+)*/);
    const marker = match?.[0];
    assert(marker, 'Explicit fixture marker required');
    const kind = match[1];
    if (kind === 'CONTEXT' && JSON.stringify(messages).length > 1048566) {
      const length = JSON.stringify(messages).length;
      console.log(JSON.stringify({marker, error: 'context_limit', length, unit: 'serializedMessageChars'}));
      response.writeHead(400, {'content-type': 'application/json'});
      response.end(JSON.stringify({error: {message: `Input length ${length} exceeds the maximum length 1048566`}}));
      return;
    }
    if (marker.includes('_ISOLATED_')) assert(!JSON.stringify(messages).includes('OOB_FILE_HOLD'), 'Other conversation leaked into this model request');
    const results = messages.slice(lastUser + 1).filter(message => message.role === 'tool').map(message => {
      const text = typeof message.content === 'string' ? message.content
        : message.content.find(part => part.type === 'text')?.text;
      assert.equal(typeof text, 'string', 'Tool result must contain its text envelope');
      const outer = JSON.parse(restoredToolOutput(text));
      assert.equal(outer.success, true);
      assert(outer.previewJson === undefined || outer.previewJson !== outer.rawResultJson,
        'Identical preview must not duplicate the model tool result');
      return {...JSON.parse(outer.rawResultJson), offloaded: text.startsWith('Earlier tool output saved in full to ')};
    });
    const previouslyCompleted = kind === 'CONTEXT' ? checkpointCount(marker) : 0;
    const count = previouslyCompleted + results.length;
    assert(count <= (kind === 'CONTEXT' ? 20 : kind === 'HTML' ? 3 : 1), 'Duplicate result');
    for (const [index, page] of results.entries()) {
      if (['HTML', 'CANCEL', 'HOLD', 'CONTEXT'].includes(kind)) {
        const offset = kind === 'CONTEXT' ? (previouslyCompleted + index) * 65536 : [0, 65536, html.length - 128][index];
        assert.equal(page.offset, offset);
        assert.equal(page.content, html.slice(offset, offset + 65536));
        assert.equal(page.hasMore, kind === 'CONTEXT' || index < 2);
        assert.equal(page.nextOffset, kind === 'CONTEXT' || index < 2 ? offset + 65536 : null);
      } else if (kind === 'TEXT') {
        assert.equal(page.content, 'second line\n');
        assert.equal(page.outputTruncated, false);
      } else if (kind === 'IMAGE') {
        assert(imageHash, 'OOB_FILE_TEST_IMAGE must point to the original PNG');
        const hashes = messages.slice(lastUser + 1).flatMap(message => Array.isArray(message.content) ? message.content : [])
          .map(part => part.image_url?.url).filter(url => url?.startsWith('data:image/'))
          .map(url => hash(Buffer.from(url.slice(url.indexOf(',') + 1), 'base64')));
        assert(hashes.includes(imageHash), 'Model did not receive unchanged original image');
      } else {
        assert.equal(page.kind, 'binary');
        assert.equal(page.contentAvailable, false);
        assert.equal(page.content, undefined);
      }
    }
    const id = ++sequence;
    const done = !['CANCEL', 'HOLD'].includes(kind) && count === (kind === 'CONTEXT' ? 20 : kind === 'HTML' ? 3 : 1);
    const args = {path: kind === 'IMAGE' ? '/workspace/oob-image-repro/large.png' : `/workspace/oob-file-repro/${names[kind]}`};
    if (kind === 'CONTEXT') args.offset = count * 65536;
    if (kind === 'HTML' && count === 1) args.offset = results[0].nextOffset;
    if (kind === 'HTML' && count === 2) args.offset = html.length - 128;
    if (kind === 'TEXT') Object.assign(args, {lineStart: 2, lineCount: 1});
    console.log(JSON.stringify({id, marker, requestBytes: raw.length, toolCount: count,
      requestChars: JSON.stringify(messages).length, offloadedCount: results.filter(page => page.offloaded).length,
      returnedChars: results.map(page => page.content?.length ?? 0), imageHash: kind === 'IMAGE' && count ? imageHash : undefined, validated: true, done}));
    if (['CANCEL', 'HOLD'].includes(kind) && count === 1) {
      assert(!held.has(marker), 'Duplicate held request');
      response.writeHead(200, {'content-type': 'text/event-stream'});
      response.write(sse({content: `${marker}_WAITING\n`}));
      const timer = setTimeout(() => {
        console.log(JSON.stringify({marker, event: 'fixture_timeout'}));
        response.destroy();
      }, 180000);
      held.set(marker, () => {
        console.log(JSON.stringify({marker, event: 'released'}));
        response.end(sse({content: `${marker}_DONE`}, 'stop') + 'data: [DONE]\n\n');
      });
      response.on('close', () => {
        clearTimeout(timer);
        held.delete(marker);
        console.log(JSON.stringify({marker, event: 'response_closed', completed: response.writableEnded}));
      });
      return;
    }
    const delta = done ? {content: `${marker}_DONE`} : {tool_calls: [{index: 0,
      id: `file_call_${marker}_${id}`, type: 'function', function: {name: 'file_read', arguments: JSON.stringify(args)}}]};
    // Exercise automatic summarization, not only offloading: completed assistant
    // progress also grows within the same user task. Deterministic, bounded data.
    if (kind === 'CONTEXT' && marker.includes('_SUMMARY_') && !done) {
      delta.content = 'Synthetic completed progress. '.repeat(500);
    }
    response.writeHead(200, {'content-type': 'text/event-stream'});
    response.end(`data: ${JSON.stringify({choices: [{index: 0, delta, finish_reason: done ? 'stop' : 'tool_calls'}]})}\n\ndata: [DONE]\n\n`);
  } catch (error) {
    console.log(JSON.stringify({error: error.message}));
    if (!response.headersSent) response.writeHead(500);
    response.end();
  }
}).listen(Number(process.env.OOB_FILE_TEST_PORT || 18769), '127.0.0.1');
