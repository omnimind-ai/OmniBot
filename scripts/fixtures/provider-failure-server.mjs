// Local-only deterministic provider for maintained failure and retry journeys.
// Its observation endpoint never returns prompts, authorization headers, or hashes.
import http from 'node:http';
import {createHash} from 'node:crypto';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';
import {respondXiaowanFailure} from './xiaowan-failure-scenarios.mjs';

const MAX_BODY_BYTES = 4 * 1024 * 1024;

function requestMarker(body) {
  const content = body.messages?.findLast(message => message.role === 'user')?.content;
  const text = typeof content === 'string' ? content : Array.isArray(content)
    ? content.filter(part => part.type === 'text').map(part => part.text).join('\n') : '';
  return text.match(/OOB_FAILURE_([A-Z]+)(?:_\d+)+/);
}

function sendJson(response, status, value, headers = {}) {
  response.writeHead(status, {'content-type': 'application/json', 'cache-control': 'no-store', ...headers});
  response.end(JSON.stringify(value));
}

function success(response, marker) {
  response.writeHead(200, {'content-type': 'text/event-stream'});
  response.end(`data: ${JSON.stringify({choices: [{index: 0, delta: {content: `${marker}_DONE`}, finish_reason: 'stop'}]})}\n\ndata: [DONE]\n\n`);
}

export function createProviderFailureServer({log = console.log} = {}) {
  const observations = new Map();
  const snapshot = observation => ({
    marker: observation.marker,
    scenario: observation.scenario,
    requestCount: observation.requestCount,
    identicalRequestBodies: observation.bodyHashes.size === 1,
    distinctRequestBodies: observation.bodyHashes.size,
    responses: {...observation.responses},
    completedResponses: observation.completedResponses,
    interruptedResponses: observation.interruptedResponses,
  });

  return http.createServer(async (request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    if (request.method === 'GET' && url.pathname === '/__fixture/requests') {
      const marker = url.searchParams.get('marker');
      if (marker !== null && !/^OOB_FAILURE_[A-Z]+(?:_\d+)+$/.test(marker)) {
        return sendJson(response, 400, {error: 'A synthetic marker is required'});
      }
      const entries = marker === null ? [...observations.values()]
        : observations.has(marker) ? [observations.get(marker)] : [];
      return sendJson(response, 200, {requests: entries.map(snapshot)});
    }
    if (request.method === 'GET' && url.pathname === '/v1/models') {
      return sendJson(response, 200, {data: [{id: 'gpt-4o', object: 'model', context_length: 128000}]});
    }
    if (request.method !== 'POST' || url.pathname !== '/v1/chat/completions') {
      response.writeHead(404).end();
      return;
    }
    try {
      const chunks = [];
      let size = 0;
      for await (const chunk of request) {
        size += chunk.length;
        if (size > MAX_BODY_BYTES) return sendJson(response, 413, {error: 'Synthetic request too large'});
        chunks.push(chunk);
      }
      const rawBody = Buffer.concat(chunks);
      const body = JSON.parse(rawBody);
      const match = requestMarker(body);
      if (!match) return sendJson(response, 400, {error: 'Synthetic marker required'});
      const [marker, scenario] = match;
      let observation = observations.get(marker);
      if (!observation) {
        if (observations.size >= 1024) return sendJson(response, 429, {error: 'Fixture marker capacity exceeded'});
        observation = {marker, scenario, requestCount: 0, bodyHashes: new Set(), responses: {},
          completedResponses: 0, interruptedResponses: 0};
        observations.set(marker, observation);
      }
      observation.requestCount += 1;
      observation.bodyHashes.add(createHash('sha256').update(rawBody).digest('hex'));
      response.once('close', () => {
        const status = String(response.statusCode);
        observation.responses[status] = (observation.responses[status] || 0) + 1;
        if (response.writableFinished) observation.completedResponses += 1;
        else observation.interruptedResponses += 1;
      });
      if (scenario === 'BACKOFF') {
        log(JSON.stringify({marker, failureScenario: scenario, phase: 'request', attempt: observation.requestCount}));
        return sendJson(response, 503, {error: {
          code: 'server_error',
          message: `${marker} synthetic backoff failure`,
        }}, {'retry-after': '10'});
      }
      if (scenario === 'RECOVER' || scenario === 'RETRYRATE') {
        log(JSON.stringify({marker, failureScenario: scenario, phase: 'request', attempt: observation.requestCount}));
        if (observation.requestCount > 2) return success(response, marker);
        const rate = scenario === 'RETRYRATE';
        return sendJson(response, rate ? 429 : 503, {error: {
          code: rate ? 'rate_limit_exceeded' : 'server_error',
          message: `${marker} synthetic temporary failure`,
        }}, rate ? {'retry-after': '1'} : {});
      }
      if (!respondXiaowanFailure(request, response, body, log)) {
        sendJson(response, 400, {error: 'Synthetic marker required'});
      }
    } catch {
      if (!response.headersSent) sendJson(response, 400, {error: 'Invalid synthetic request'});
      else response.end();
    }
  });
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  const server = createProviderFailureServer();
  server.listen(Number(process.env.OOB_FAILURE_PORT || 18879), '127.0.0.1', () => {
    console.log(`Local failure fixture ready port=${server.address().port}`);
  });
  process.once('SIGTERM', () => { server.closeAllConnections(); server.close(); });
}
