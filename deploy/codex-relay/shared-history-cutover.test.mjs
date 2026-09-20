import test from 'node:test';
import assert from 'node:assert/strict';
import {CodexAcpClient, CodexAcpServer} from './codex-acp-v2-module.mjs';

test('host message identity reaches the backend and returns on a live user item', async () => {
  let sent;
  const client = Object.assign(Object.create(CodexAcpClient.prototype), {
    refreshSkills: async () => {},
    codexClient: {runTurn: async params => {sent = params; return {}; }},
  });
  await client.sendPrompt({sessionId: 'session', prompt: [{type: 'text', text: 'hello'}],
    _meta: {'dev.omnimind/clientMessageId': 'host-user'}},
    {sandboxPolicy: {type: 'dangerFullAccess'}}, {model: 'test', effort: 'low'}, null, false, '/tmp', []);
  assert.equal(sent.clientUserMessageId, 'host-user');
  let update;
  await CodexAcpServer.prototype.publishSharedUserMessage.call({
    connection: {notify: async (method, value) => {update = value.update;}},
    createUserMessageUpdates: () => [{content: {type: 'text', text: 'hello'}}],
  }, {method: 'item/started', params: {threadId: 'session', turnId: 'turn',
    item: {type: 'userMessage', id: 'backend-user', clientId: 'host-user'}}});
  assert.equal(update.messageId, 'backend-user');
  assert.equal(update._meta.codex.clientId, 'host-user');
});

test('load uses the atomic resume snapshot and installs observation before later I/O', async () => {
  const order = [];
  const thread = {id: 'test-session', turns: [{id: 'turn', items: []}]};
  const client = Object.assign(Object.create(CodexAcpClient.prototype), {
    refreshSkills: async () => {},
    createSessionConfig: async () => ({}),
    getResumeModelProvider: async () => 'openai',
    codexClient: {threadResume: async params => {
      assert.equal(params.excludeTurns, false);
      return {thread, model: 'test-model', modelProvider: 'openai'};
    }, threadReadWithHistory: () => assert.fail('Must not take another history snapshot')},
    fetchAvailableModels: async () => {order.push('models'); return [];},
    createModelId: () => 'test-model',
    getCollaborationMode: () => null,
  });
  const result = await client.loadSession({sessionId: thread.id, cwd: '/tmp', mcpServers: []},
    () => order.push('subscribed'));
  assert.equal(result.thread, thread);
  assert.deepEqual(order, ['subscribed', 'models']);
});

test('resume queues post-snapshot events until history is projected, in backend order', async () => {
  const events = [{method: 'item/agentMessage/delta', params: {delta: 'A'}},
    {method: 'item/completed', params: {item: {id: 'item', text: 'A'}}}];
  const order = [];
  let listener;
  const state = {asyncTasks: {reconcile: async () => {}}};
  const server = {
    providerUpdate: null,
    codexAcpClient: {codexClient: {onServerNotification: (id, callback) => {
      assert.equal(id, 'test-session'); listener = callback;
    }}},
    getOrCreateSessionWithHistory: async (params, onSubscribed) => {
      onSubscribed();
      listener(events[0]); // Model/config initialization is still in progress.
      return {sessionId: params.sessionId, modelState: {availableModels: []}, modeState: {}, thread: {}};
    },
    streamThreadHistory: async () => {order.push('history'); listener(events[1]);},
    getSessionState: () => state,
    observeSharedSession: (observed, pending) => {
      assert.equal(observed, state); assert.deepEqual(pending, events);
      order.push(...pending.map(event => event.method));
    },
    createSessionConfigOptionsResponse: () => ({}),
  };
  await CodexAcpServer.prototype.loadSession.call(server, {sessionId: 'test-session'});
  assert.deepEqual(order, ['history', ...events.map(event => event.method)]);
});
