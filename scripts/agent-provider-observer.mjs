// Test-only transparent Provider relay. No retry, response rewriting, or Agent lifecycle.
// Bind only to host loopback; an Android emulator reaches this via 10.0.2.2.
// Usage: OOB_OBSERVER_UPSTREAM=https://provider.example node scripts/agent-provider-observer.mjs
// Configure ONLY an isolated emulator test Provider to http://10.0.2.2:PORT.
// Logs contain model IDs/status only. Credentials remain in transit, never in logs.
import http from 'node:http';
import {Readable} from 'node:stream';
import {pipeline} from 'node:stream/promises';
import {pathToFileURL} from 'node:url';

export function createProviderObserver(upstream, observe = console.log) {
  const base = new URL(upstream);
  if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password ||
      base.search || base.hash || base.pathname !== '/') {
    throw new Error('Upstream must be a credential-free HTTP(S) origin');
  }
  let sequence = 0;
  return http.createServer(async (req, res) => {
    const id = ++sequence;
    const startedAt = Date.now();
    const controller = new AbortController();
    res.on('close', () => { if (!res.writableEnded) controller.abort(); });
    try {
      const path = new URL(req.url, 'http://observer.invalid');
      if (!['/v1/models', '/v1/chat/completions', '/v1/responses', '/v1/messages'].includes(path.pathname)
          || !['GET', 'POST'].includes(req.method)) {
        res.writeHead(404).end();
        return;
      }
      const chunks = [];
      for await (const chunk of req) chunks.push(chunk);
      const body = Buffer.concat(chunks);
      const payload = body.length ? JSON.parse(body) : {};
      const model = payload.model;
      const effort = payload.reasoning_effort ?? payload.reasoning?.effort ?? payload.output_config?.effort;
      const allowedEfforts = ['none', 'off', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'];
      const reasoning = {
        ...(allowedEfforts.includes(effort) ? {effort} : {}),
        ...(['enabled', 'disabled', 'adaptive'].includes(payload.thinking?.type)
          ? {thinkingType: payload.thinking.type} : {}),
        ...(Number.isSafeInteger(payload.thinking?.budget_tokens)
          ? {thinkingBudget: payload.thinking.budget_tokens} : {}),
      };
      observe({id, phase: 'request', at: new Date(startedAt).toISOString(), endpoint: path.pathname,
        ...(typeof model === 'string' ? {model} : {}), ...reasoning});
      const headers = new Headers();
      for (const [key, value] of Object.entries(req.headers)) {
        if (value != null && !['host', 'connection', 'content-length', 'transfer-encoding', 'accept-encoding'].includes(key)) {
          headers.set(key, Array.isArray(value) ? value.join(', ') : value);
        }
      }
      headers.set('accept-encoding', 'identity');
      const response = await fetch(new URL(path.pathname + path.search, base), {
        method: req.method, headers, body: body.length ? body : undefined,
        signal: controller.signal, redirect: 'manual',
      });
      observe({id, phase: 'response', status: response.status, elapsedMs: Date.now() - startedAt});
      const returnedHeaders = Object.fromEntries([...response.headers].filter(([key]) =>
        !['content-encoding', 'content-length', 'transfer-encoding', 'connection'].includes(key)));
      res.writeHead(response.status, returnedHeaders);
      if (response.body) await pipeline(Readable.fromWeb(response.body), res);
      else res.end();
    } catch (error) {
      observe({id, phase: 'transport_error', elapsedMs: Date.now() - startedAt,
        clientDisconnected: controller.signal.aborted,
        errorType: error instanceof Error ? error.name : 'UnknownError'});
      if (!res.headersSent) res.writeHead(502);
      res.end();
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const server = createProviderObserver(process.env.OOB_OBSERVER_UPSTREAM,
    event => console.log(JSON.stringify(event)));
  server.listen(Number(process.env.OOB_OBSERVER_PORT || 0), '127.0.0.1', () => {
    console.log(JSON.stringify({phase: 'listening', port: server.address().port}));
  });
}
