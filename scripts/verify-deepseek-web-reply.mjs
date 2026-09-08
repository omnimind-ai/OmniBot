// Verify an actual assistant Markdown paragraph, not the echoed user prompt/title.
// Usage: node scripts/verify-deepseek-web-reply.mjs CDP_PORT MARKER [--reload]
import assert from 'node:assert/strict';
const [port, marker, flag] = process.argv.slice(2);
assert(/^\d+$/.test(port || '') && /^[A-Z][A-Z0-9_]+$/.test(marker || '') &&
  (!flag || flag === '--reload'));
const tabs = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const matches = tabs.filter(t => t.type === 'page' &&
  (t.title === 'DeepSeek Harness' || t.title.endsWith(' — DeepSeek Harness')));
assert.equal(matches.length, 1, 'Expected one live DeepSeek Web conversation tab');
const ws = new WebSocket(matches[0].webSocketDebuggerUrl);
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
let id = 0;
const pending = new Map();
ws.onmessage = ({data}) => {
  const m = JSON.parse(data);
  if (!m.id) return;
  const p = pending.get(m.id);
  pending.delete(m.id);
  m.error ? p?.reject(new Error('CDP request failed')) : p?.resolve(m.result);
};
const call = (method, params = {}) => new Promise((resolve, reject) => {
  pending.set(++id, {resolve, reject});
  ws.send(JSON.stringify({id, method, params}));
});
const timeout = setTimeout(() => { console.error('Reply observation timed out'); process.exit(1); }, 30000);
try {
  if (flag === '--reload') {
    const draft = await call('Runtime.evaluate', {expression:
      'document.querySelector("[role=textbox][contenteditable=true]")?.innerText.trim() || ""', returnByValue: true});
    assert.equal(draft.result.value, '', 'Do not reload an unsent draft');
    await call('Page.reload');
    await new Promise(resolve => setTimeout(resolve, 10000));
  }
  const {result, exceptionDetails} = await call('Runtime.evaluate', {expression:
    `JSON.stringify({userCount:[...document.querySelectorAll('[class*="_userRow"] span')].filter(e=>e.textContent===${JSON.stringify(`Reply ${marker}`)}).length,assistantCount:[...document.querySelectorAll('[class*="_markdown_"] p')].filter(e=>e.textContent===${JSON.stringify(marker)}).length,sendRestored:!!document.querySelector('button[aria-label="Send message"]')})`,
    returnByValue: true});
  assert(!exceptionDetails, 'Could not inspect rendered conversation');
  const state = JSON.parse(result.value);
  console.log(JSON.stringify({marker, reloaded: !!flag, ...state}));
  assert.equal(state.userCount, 1, 'Expected one actual user message');
  assert.equal(state.assistantCount, 1, 'Expected one actual assistant reply');
  assert(state.sendRestored, 'Composer has not returned to send state');
} finally {
  clearTimeout(timeout);
  ws.close();
}
