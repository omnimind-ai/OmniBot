// One bounded, synthetic probe. Credentials enter via stdin, never arguments or logs.
import { realpath, mkdir, writeFile, rename } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';

export async function checkProvider(config, fetchImpl = fetch) {
  const endpoint = new URL(config.endpoint);
  if (endpoint.protocol !== 'https:' && !(endpoint.protocol === 'http:' &&
      ['127.0.0.1', 'localhost', '[::1]'].includes(endpoint.hostname))) {
    throw new Error('Use HTTPS (HTTP is allowed only for a local test provider)');
  }
  if (endpoint.username || endpoint.password || endpoint.search || endpoint.hash) {
    throw new Error('Endpoint must not contain credentials, query parameters or fragments');
  }
  if (typeof config.model !== 'string' || !config.model.trim()) throw new Error('Configured model is required');
  // Match the native Provider: custom headers override generated defaults,
  // ignoring case. Do not silently replace the configured authentication.
  const headers = new Headers({ 'Content-Type': 'application/json' });
  if (config.apiKey?.trim()) headers.set('Authorization', `Bearer ${config.apiKey.trim()}`);
  for (const [name, value] of Object.entries(config.headers ?? {})) {
    const normalized = name.trim().toLowerCase();
    if (['host', 'content-length', 'connection', 'transfer-encoding'].includes(normalized)) continue;
    if (['authorization', 'x-api-key', 'api-key'].includes(normalized) && !String(value).trim()) {
      throw new Error('Authentication header cannot be empty');
    }
    headers.set(name.trim(), value);
  }
  const probe = config.probe ?? 'text';
  if (!['text', 'vision_tool'].includes(probe)) throw new Error('Unsupported probe');
  const content = probe === 'text' ? 'Reply with exactly PROVIDER_CHECK_OK.' : [
    { type: 'text', text: 'Call report_color with the image color in lowercase English.' },
    { type: 'image_url', image_url: { url: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAJklEQVR4nO3NMQ0AAAwDoPo33arYsQQMkB6LQCAQCAQCgUAg+BIMi1X0pjxKe0gAAAAASUVORK5CYII=' } },
  ];
  const tools = [{ type: 'function', function: { name: 'report_color', description: 'Report image color.',
    parameters: { type: 'object', properties: { color: { type: 'string' } }, required: ['color'] } } }];
  const result = { schemaVersion: 1, probe, observedAt: new Date().toISOString(), status: 'needs_verification' };
  try {
    const response = await fetchImpl(endpoint, {
      method: 'POST', redirect: 'error', signal: AbortSignal.timeout(20000),
      headers,
      body: JSON.stringify({ model: config.model, stream: false, max_tokens: 1024,
        messages: [{ role: 'user', content }], ...(probe === 'vision_tool' ? { tools } : {}) }),
    });
    result.httpStatus = response.status;
    // Do not persist/print provider bodies: even error messages can echo secrets.
    if (!response.ok) {
      result.failureKind = response.status === 401 ? 'authentication' :
        response.status === 429 ? 'rate_or_quota' : response.status >= 500 ? 'service_unavailable' : 'request_rejected';
      await response.body?.cancel();
      return result;
    }
    const payload = await response.json();
    const message = payload.choices?.[0]?.message;
    const call = message?.tool_calls?.[0];
    const passed = probe === 'text'
      ? typeof message?.content === 'string' && message.content.trim() === 'PROVIDER_CHECK_OK'
      : call?.function?.name === 'report_color' && JSON.parse(call.function.arguments).color === 'white';
    result.status = passed ? `${probe}_probe_passed` : 'needs_verification';
    if (!passed) result.failureKind = 'unexpected_response';
  } catch {
    result.failureKind = 'transport_or_invalid_response';
  }
  return result;
}

if (process.argv[1] && await realpath(process.argv[1]) === fileURLToPath(import.meta.url)) {
  let result;
  try {
    let input = '';
    for await (const chunk of process.stdin) {
      input += chunk;
      if (input.length > 65536) throw new Error('Input too large');
    }
    result = await checkProvider(JSON.parse(input));
  } catch {
    // A rejected new check supersedes any prior successful probe. Persist it
    // through the same path so agents cannot read stale success after failure.
    result = { schemaVersion: 1, observedAt: new Date().toISOString(),
      status: 'check_failed', failureKind: 'configuration' };
  }
  try {
    const data = resolve(dirname(fileURLToPath(import.meta.url)), '../data');
    await mkdir(data, { recursive: true });
    const target = resolve(data, 'provider-check.json');
    await writeFile(`${target}.tmp`, JSON.stringify(result, null, 2), { mode: 0o600 });
    await rename(`${target}.tmp`, target);
    console.log(JSON.stringify(result));
    process.exitCode = result.status.endsWith('_probe_passed') ? 0 : 1;
  } catch {
    console.log(JSON.stringify({ status: 'check_failed', failureKind: 'storage' }));
    process.exitCode = 1;
  }
}
