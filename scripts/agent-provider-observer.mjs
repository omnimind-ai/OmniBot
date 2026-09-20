// Test-only transparent Provider relay. No retry, response rewriting, or Agent lifecycle.
// Bind only to host loopback; an Android emulator reaches this via 10.0.2.2.
// Usage: OOB_OBSERVER_UPSTREAM=https://provider.example node scripts/agent-provider-observer.mjs
// Configure ONLY an isolated emulator test Provider to http://10.0.2.2:PORT.
// Default logs contain model IDs/status only. Explicit synthetic-error capture
// additionally records bounded standard error fields for OOB_LIVE_AUTO_COMPACT
// tasks, with forwarded credentials redacted. Never use it for private prompts.
import http from 'node:http';
import {Readable, Transform} from 'node:stream';
import {pipeline} from 'node:stream/promises';
import {pathToFileURL} from 'node:url';

export function createProviderObserver(upstream, observe = console.log, {captureSyntheticErrors = false} = {}) {
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
      const lastUser = payload.messages?.findLast(m => m.role === 'user');
      const userText = typeof lastUser?.content === 'string' ? lastUser.content :
        (Array.isArray(lastUser?.content) ? lastUser.content.filter(p => p.type === 'text').map(p => p.text).join('\n') : '');
      const syntheticMarker = userText.match(/\b(OOB_LIVE_AUTO_COMPACT_\d+)(?:_DONE)?\b/)?.[1];
      const diagnosticEnabled = captureSyntheticErrors && Boolean(syntheticMarker);
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
        ...(typeof model === 'string' ? {model} : {}), ...reasoning,
        ...(diagnosticEnabled ? {marker:syntheticMarker, requestBytes:body.length,
          messages:payload.messages?.length, tools:payload.tools?.length,
          maxTokens:payload.max_tokens, maxCompletionTokens:payload.max_completion_tokens} : {})});
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
      if (response.body && !response.ok && diagnosticEnabled) {
        // Observe a bounded error prefix without changing bytes, timing, retries or ownership.
        const parts = []; let bytes = 0;
        const tap = new Transform({transform(chunk, encoding, next) {
          if (bytes < 65536) parts.push(chunk.subarray(0, 65536 - bytes));
          bytes += chunk.length;
          next(null, chunk);
        }});
        await pipeline(Readable.fromWeb(response.body), tap, res);
        let detail;
        try {
          const parsed = JSON.parse(Buffer.concat(parts).toString('utf8'));
          const source = parsed.error && typeof parsed.error === 'object' ? parsed.error : parsed;
          const credentials = Object.entries(req.headers)
            .filter(([key]) => /authorization|api.?key|token|secret/i.test(key))
            .flatMap(([,value]) => [String(value), String(value).replace(/^Bearer\s+/i, '')]);
          const redact = value => {
            let text = value;
            for (const secret of credentials.filter(Boolean).sort((a,b) => b.length-a.length)) text = text.replaceAll(secret, '[REDACTED]');
            return text.replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi, 'Bearer [REDACTED]').slice(0,4096);
          };
          detail = Object.fromEntries(['code','type','message','param'].filter(key =>
            typeof source[key] === 'string' || typeof source[key] === 'number')
            .map(key => [key, redact(String(source[key]))]));
        } catch { detail = {unparsed:true, bytes}; }
        observe({id, phase:'synthetic_provider_error', marker:syntheticMarker, status:response.status, detail});
      } else if (response.body) await pipeline(Readable.fromWeb(response.body), res);
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
    event => console.log(JSON.stringify(event)),
    {captureSyntheticErrors:process.env.OOB_OBSERVER_CAPTURE_SYNTHETIC_ERRORS === '1'});
  server.listen(Number(process.env.OOB_OBSERVER_PORT || 0), '127.0.0.1', () => {
    console.log(JSON.stringify({phase: 'listening', port: server.address().port}));
  });
}
