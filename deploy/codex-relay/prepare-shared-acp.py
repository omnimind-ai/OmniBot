#!/usr/bin/env python3
"""Opt-in snapshot metadata for pinned upstream codex-acp; no extra Agent loop.
Usage: prepare-shared-acp.py PACKAGE_DIRECTORY OUTPUT_FILE
Upstream @agentclientprotocol/codex-acp 1.11.0, Apache-2.0.
The original installed dependency is left intact; keep its LICENSE with deployment.
"""
import argparse
import hashlib
import json
import pathlib
import sys

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('package', type=pathlib.Path)
parser.add_argument('output', type=pathlib.Path)
parser.add_argument('--session-observation', action='store_true',
                    help='Experimental upstream session subscription probe; not a v2 adapter')
parser.add_argument('--v2-module', action='store_true',
                    help='Export an experimental upstream module for shared-acp-v2.mjs')
args = parser.parse_args()
package, output = args.package, args.output
assert json.loads((package / 'package.json').read_text())['version'] == '1.11.0'
raw = (package / 'dist/index.js').read_bytes()
assert hashlib.sha256(raw).hexdigest() == '3527bdaf90a219175c742576963e6d9e943e4ea5fbdbc3e04e7f57f9a9e11343', 'Upstream changed; review before patching'
source = raw.decode()
start = source.index('  async loadSession(params) {')
end = source.index('  async resumeSession(params) {', start)
section = source[start:end]
needle = '      models: modelState,\n'
assert section.count(needle) == 1
section = section.replace(needle, '''      ...(params._meta?.["dev.omnimind.codex/includeThreadSnapshot"] === true
        ? { _meta: { "dev.omnimind.codex/threadSnapshot": thread } } : {}),
''' + needle)
source = source[:start] + section + source[end:]
if args.session_observation or args.v2_module:
    def replace_once(old, new):
        global source
        assert source.count(old) == 1, 'Upstream seam changed: ' + old[:80]
        source = source.replace(old, new)

    # Reuse the upstream notification registry and its per-session ordering.
    # The registry replaces listeners; closeSession already removes them.
    observer = '''  observeSharedSession(sessionState) {
    const handler = new CodexEventHandler(
      this.connection, sessionState,
      clientSupportsPlanUpdates(this.clientCapabilities),
      clientSupportsTypedSessionFailures(this.clientCapabilities),
      this.sessionFailureEpoch, sessionState.subagents,
      (updated) => this.handleAccountUpdated(updated)
    );
    this.codexAcpClient.codexClient.onServerNotification(sessionState.sessionId,
      (event) => this.codexAcpClient.enqueueSessionNotification(
        sessionState.sessionId, () => handler.handleNotification(event)));
  }
'''
    replace_once('  async loadSession(params) {', observer + '  async loadSession(params) {')
    replace_once('    await this.streamThreadHistory(sessionId, thread);',
                 '    await this.streamThreadHistory(sessionId, thread);\n'
                 '    this.observeSharedSession(this.getSessionState(sessionId));')
    replace_once('    logger.log("Session resumed", {',
                 '    this.observeSharedSession(this.getSessionState(sessionId));\n'
                 '    logger.log("Session resumed", {')
    replace_once('      activePrompt.complete();\n    }\n  }',
                 '      activePrompt.complete();\n'
                 '      if (!this.sessionIsClosing(params.sessionId)) this.observeSharedSession(sessionState);\n'
                 '    }\n  }')
    # A later local prompt must replace the observer with the upstream prompt
    # handler, including the upstream subagent/permission handling.
    replace_once('      existing.current = subscription;\n      return;',
                 '      existing.current = subscription;\n'
                 '      this.client.onServerNotification(subscription.rootSessionId, (event) => {\n'
                 '        this.discover(existing, event);\n'
                 '        existing.current.dispatch(event);\n'
                 '      });\n      return;')
    # Carry identity supplied by the backend itself. Never infer it from text
    # or the currently selected conversation. This metadata is diagnostic;
    # it is not a replacement for the v2 state_update lifecycle.
    replace_once('      await this.session.update(updateEvent, this.subagents.notificationSessionId(notification));',
                 '''      const turnId = notification.params?.turnId;
      if (turnId) updateEvent = { ...updateEvent, _meta: {
        ...updateEvent._meta, codex: { ...updateEvent._meta?.codex, turnId }
      }};
      await this.session.update(updateEvent, this.subagents.notificationSessionId(notification));''')
if args.v2_module:
    # The backend persists clientUserMessageId as userMessage.clientId.
    # Carry the host's existing message identity across a lost admission ACK.
    replace_once('''    return await this.codexClient.runTurn({
      threadId: request.sessionId,
      input,''', '''    return await this.codexClient.runTurn({
      threadId: request.sessionId,
      clientUserMessageId: request._meta?.["dev.omnimind/clientMessageId"],
      input,''')
    # Codex 0.154.0 ThreadListenerCommand::SendThreadResumeResponse returns
    # history and subscribes atomically. Reading history again loses that
    # boundary. Buffer only notifications after that response until the
    # upstream session state and its history projection are ready.
    a = source.index('  async loadSession(request, onSubscribed) {')
    b = source.index('  async readSessionThread(', a)
    section = source[a:b]
    assert section.count('excludeTurns: true') == 1
    section = section.replace('excludeTurns: true', 'excludeTurns: false')
    history_start = section.index('    const thread = response.thread.historyMode')
    history_end = section.index('    const codexModels =', history_start)
    section = section[:history_start] + '    const thread = response.thread;\n' + section[history_end:]
    source = source[:a] + section + source[b:]
    replace_once('  async getOrCreateSessionWithHistory(request) {',
                 '  async getOrCreateSessionWithHistory(request, onSubscribed) {')
    replace_once('''        () => this.codexAcpClient.loadSession(request, () => {
          subscribed = true;
        })''', '''        () => this.codexAcpClient.loadSession(request, () => {
          subscribed = true;
          onSubscribed?.();
        })''')
    replace_once('''    logger.log("Loading session...", { sessionId: params.sessionId });
    const {''', '''    logger.log("Loading session...", { sessionId: params.sessionId });
    const pendingNotifications = [];
    const {''')
    replace_once('''    } = await this.getOrCreateSessionWithHistory(params);
    await this.streamThreadHistory(sessionId, thread);
    this.observeSharedSession(this.getSessionState(sessionId));''', '''    } = await this.getOrCreateSessionWithHistory(params, () => {
      this.codexAcpClient.codexClient.onServerNotification(params.sessionId,
        event => pendingNotifications.push(event));
    });
    await this.streamThreadHistory(sessionId, thread);
    this.observeSharedSession(this.getSessionState(sessionId), pendingNotifications);''')
    replace_once('  observeSharedSession(sessionState) {', '''  async publishSharedUserMessage(event) {
    if (event.method !== "item/started" || event.params.item.type !== "userMessage") return;
    await this.connection.notify("session/update", {
      sessionId: event.params.threadId,
      update: {sessionUpdate: "user_message", messageId: event.params.item.id,
        content: this.createUserMessageUpdates(event.params.item).map(update => update.content),
        _meta: {codex: {turnId: event.params.turnId, clientId: event.params.item.clientId}}}
    });
  }
  observeSharedSession(sessionState, pendingNotifications = []) {''')
    replace_once('''    this.codexAcpClient.codexClient.onServerNotification(sessionState.sessionId,
      (event) => this.codexAcpClient.enqueueSessionNotification(
        sessionState.sessionId, () => handler.handleNotification(event)));''',
                 '''    const dispatch = event => this.codexAcpClient.enqueueSessionNotification(
        sessionState.sessionId, async () => {
          await this.publishSharedUserMessage(event);
          await handler.handleNotification(event);
        });
    this.codexAcpClient.codexClient.onServerNotification(sessionState.sessionId, dispatch);
    for (const event of pendingNotifications) dispatch(event);''')
    replace_once('''        async (event) => {
          await observeInteraction(event);''', '''        async (event) => {
          await this.publishSharedUserMessage(event);
          await observeInteraction(event);''')
    # Terminal notifications come from the authoritative backend. Do not
    # generate a second completion from the legacy prompt response.
    replace_once('''        this.sessionState.currentTurnId = notification.params.turn.id;
        await this.flushPendingErrors();
        return null;''', '''        this.sessionState.currentTurnId = notification.params.turn.id;
        await this.flushPendingErrors();
        return {sessionUpdate: "state_update", state: "running",
          _meta: {codex: {turnId: notification.params.turn.id}}};''')
    replace_once('''        this.sessionState.currentTurnId = null;
        return null;
      case "thread/tokenUsage/updated":''', '''        this.sessionState.currentTurnId = null;
        return {sessionUpdate: "state_update", state: "idle",
          stopReason: notification.params.turn.status === "interrupted" ? "cancelled"
            : notification.params.turn.status === "failed" ? "error" : "end_turn",
          _meta: {codex: {turnId: notification.params.turn.id,
            error: notification.params.turn.error}}};
      case "thread/tokenUsage/updated":''')
    # Whole-message history upserts make repeated resume idempotent by ID.
    # This is a version boundary using upstream item conversion, not a second
    # history store or model loop.
    a = source.index('  async streamThreadHistory(sessionId, thread) {')
    b = source.index('  async streamNativeThreadHistory(', a)
    source = source[:a] + '''  async streamThreadHistory(sessionId, thread) {
    const session = new ACPSessionConnection(this.connection, sessionId);
    const sessionState = this.getSessionState(sessionId);
    await this.publishThreadHistoryTitle(session, sessionState, thread);
    for (const turn of thread.turns) {
      for (const item of turn.items) {
        const updates = await this.createHistoryUpdates(item, sessionState);
        const messages = new Map();
        for (const update of updates) {
          const meta = {...update._meta, codex: {...update._meta?.codex, turnId: turn.id,
            ...(item.type === "userMessage" && item.clientId ? {clientId: item.clientId} : {})}};
          if (["agent_message_chunk", "user_message_chunk", "agent_thought_chunk"].includes(update.sessionUpdate)) {
            if (!update.messageId) throw new Error("Backend history message has no identity");
            const current = messages.get(update.messageId) || {
              ...update, sessionUpdate: update.sessionUpdate.replace("_chunk", ""), content: [], _meta: meta};
            current.content.push(update.content);
            messages.set(update.messageId, current);
          } else await session.update({...update, _meta: meta});
        }
        for (const message of messages.values()) await session.update(message);
      }
    }
    const last = thread.turns.at(-1);
    sessionState.currentTurnId = last?.status === "inProgress" ? last.id : null;
    await session.update({sessionUpdate: "state_update",
      state: last?.status === "inProgress" ? "running" : "idle",
      _meta: {codex: {turnId: last?.id}}});
  }
''' + source[b:]
    start = source.index('  async completeItemEvent(event) {')
    old = '\n      case "agentMessage":\n        this.rememberAgentMessagePhase(event.item);\n        return null;'
    offset = source.index(old, start)
    source = source[:offset] + '\n      case "agentMessage":\n        this.rememberAgentMessagePhase(event.item);\n        return {sessionUpdate: "agent_message", messageId: event.item.id, content: [{type: "text", text: event.item.text}]};' + source[offset + len(old):]
    # Importing the generated module must not start another stdio server.
    replace_once('  startAcpServer();\n}', '  // The official v2 SDK entry point owns the ACP connection.\n}')
    source += '\nexport {CodexAcpServer, CodexAcpClient, CodexAppServerClient, startCodexConnection};\n'
output.write_text(source)
output.chmod(0o700)
output.with_suffix(output.suffix + '.LICENSE').write_bytes((package / 'LICENSE').read_bytes())
print('Prepared opt-in ACP snapshot adapter:', output)
