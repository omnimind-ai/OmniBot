#!/usr/bin/env node
// Experimental v2 entry point for the pinned upstream Codex ACP implementation.
// The upstream agent owns execution, permissions, sessions and model resolution.
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { Readable, Writable } from 'node:stream';
import { readFileSync } from 'node:fs';
if (process.argv.includes('--version')) {
  const metadata = JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8'));
  console.log(`${metadata.name} ${metadata.version}`);
  process.exit(0);
}
const require = createRequire(import.meta.url);
const { agent, ndJsonStream } = await import(pathToFileURL(require.resolve('@agentclientprotocol/sdk/experimental/v2')));
const modulePath = process.env.OOB_CODEX_ACP_MODULE;
if (!modulePath) throw new Error('OOB_CODEX_ACP_MODULE must name the prepared upstream module');
const upstream = await import(pathToFileURL(modulePath));
const codexPath = process.env.CODEX_PATH;
if (!process.env.OOB_SHARED_CODEX_SOCKET && !process.env.OOB_SHARED_CODEX_URL)
  throw new Error('A shared Codex endpoint is required');
const processState = { connection: upstream.startCodexConnection(codexPath), codexPath, stderr: '' };
let impl;

function notification(params) {
  const u = params.update;
  if (u.sessionUpdate === 'tool_call') return { ...params, update: { ...u, sessionUpdate: 'tool_call_update' } };
  if (u.sessionUpdate === 'available_commands_update') return { ...params, update: {
    ...u, availableCommands: u.availableCommands.map(c => ({ ...c,
      ...(c.input ? {input: {type: 'unstructured', ...c.input}} : {}) }))
  }};
  return params;
}

const app = agent({name: 'codex-shared-acp-v2'}).onConnect(connection => {
  // The official v2 SDK validates framing and schemas. The existing agent's
  // client boundary performs only version-specific payload conversion.
  const client = {
    notify(method, params) {
      if (method === 'session/update') return connection.client.notify(method, notification(params));
      return connection.client.notify(method, params);
    },
    request(method, params, options) {
      if (method === 'session/request_permission' && params.toolCall) {
        const {toolCall, ...rest} = params;
        return connection.client.request(method, {
          ...rest, title: toolCall.title || 'Codex permission',
          subject: {type: 'tool_call', toolCallId: toolCall.toolCallId}
        }, options);
      }
      return connection.client.request(method, params, options);
    }
  };
  const backend = new upstream.CodexAppServerClient(processState.connection.connection);
  impl = new upstream.CodexAcpServer(client, new upstream.CodexAcpClient(backend),
    undefined, undefined, undefined, processState);
});

app.onRequest('initialize', async ({params}) => {
  const result = await impl.initialize({protocolVersion: 1, clientInfo: params.info, clientCapabilities: {}});
  return {protocolVersion: 2, info: result.agentInfo,
    capabilities: {session: {prompt: {image: {}, embeddedContext: {}}, mcp: {stdio: {}, http: {}}, delete: {}}},
    authMethods: (result.authMethods || []).map(({id, ...m}) => ({...m, methodId: id, type: 'agent'}))};
});
const configResponse = r => ({...(r.configOptions ? {configOptions: r.configOptions} : {}),
  ...(r._meta ? {_meta: r._meta} : {})});
app.onRequest('session/new', async ({params}) => {
  const r = await impl.newSession({...params, mcpServers: params.mcpServers || []});
  impl.observeSharedSession(impl.getSessionState(r.sessionId));
  return {sessionId: r.sessionId, ...configResponse(r)};
});
app.onRequest('session/resume', async ({params}) => {
  if (params.replayFrom && params.replayFrom.type !== 'start') throw new Error('Unsupported replay cursor');
  const p = {...params, mcpServers: params.mcpServers || []};
  return configResponse(await (params.replayFrom ? impl.loadSession(p) : impl.resumeSession(p)));
});
app.onRequest('session/list', ({params}) => impl.listSessions(params));
app.onRequest('session/delete', ({params}) => impl.deleteSession(params));
app.onRequest('session/close', ({params}) => impl.closeSession(params));
app.onRequest('session/set_config_option', ({params}) => impl.setSessionConfigOption(params));
app.onRequest('auth/login', ({params, requestId}) => impl.authenticate({methodId: params.methodId}, requestId));
app.onRequest('auth/logout', () => impl.logout());
app.onNotification('session/cancel', ({params}) => impl.cancel(params));
app.onRequest('session/prompt', ({params, signal}) => new Promise((resolve, reject) => {
  // Upstream invokes this callback only after the backend accepts the turn.
  // Completion is reported by backend-derived v2 state_update, not this reply.
  let accepted = false;
  impl.prompt(params, signal, () => { accepted = true; resolve({_meta: {codex: {turnId: impl.getSessionState(params.sessionId).currentTurnId}}}); }).then(() => {
    if (!accepted) resolve({});
  }, error => {
    if (!accepted) reject(error);
    else console.error('Accepted Codex prompt failed:', error.message);
  });
}));
app.connect(ndJsonStream(Writable.toWeb(process.stdout), Readable.toWeb(process.stdin)));
process.stdin.on('close', () => processState.connection.process.stdin.end());
