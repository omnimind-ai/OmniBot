// Diagnose an existing Android Chrome tab via an explicit adb-forwarded CDP port.
// Usage: node scripts/verify-agent-web-page.mjs PORT 'Kimi Code Web' [--reload]
// --reload is only for a test page without unsaved input. Never prints URLs,
// credentials, page text, or network bodies. This is a rendering gate, not chat acceptance.
import assert from 'node:assert/strict';
const [port, title, flag] = process.argv.slice(2);
assert(/^\d+$/.test(port || '') && title && (!flag || flag === '--reload'));
const tabs = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const matches = tabs.filter(t => t.type === 'page' && t.title === title);
assert.equal(matches.length, 1, 'Expected one matching browser tab');
const ws = new WebSocket(matches[0].webSocketDebuggerUrl);
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
let nextId = 0;
const pending = new Map();
const exceptions = [];
const timeout = setTimeout(() => { console.error('Web rendering observation timed out'); process.exit(1); }, 30000);
const safeError = text => text.split('\n')[0].replace(/https?:\S+/g, '[url]');
ws.onmessage = ({data}) => {
  const message = JSON.parse(data);
  if (message.id) {
    const handler = pending.get(message.id);
    pending.delete(message.id);
    message.error ? handler?.reject(new Error(message.error.message)) : handler?.resolve(message.result);
  } else if (message.method === 'Runtime.exceptionThrown') {
    const details = message.params.exceptionDetails;
    exceptions.push(safeError(details.exception?.description || details.text));
  }
};
const call = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++nextId;
  pending.set(id, {resolve, reject});
  ws.send(JSON.stringify({id, method, params}));
});
try {
  await call('Runtime.enable');
  await call('Page.enable');
  if (flag === '--reload') {
    await call('Page.reload');
    // A bounded test observation window, not an application retry/timeout policy.
    await new Promise(resolve => setTimeout(resolve, 10000));
  }
  const {result} = await call('Runtime.evaluate', {expression:
    'JSON.stringify({ready:document.readyState,arrayToSorted:typeof Array.prototype.toSorted,bodyCharacters:document.body?.innerText.trim().length||0,controls:document.querySelectorAll("button,input,textarea,[contenteditable=true]").length})',
    returnByValue: true});
  const rendering = JSON.parse(result.value);
  console.log(JSON.stringify({title, rendering, exceptions, conversationAcceptance: 'not tested'}));
  assert.equal(exceptions.length, 0, 'Browser script exceptions detected');
  assert(rendering.bodyCharacters > 0 && rendering.controls > 0, 'Blank or non-interactive Web UI');
} finally {
  clearTimeout(timeout);
  ws.close();
}
