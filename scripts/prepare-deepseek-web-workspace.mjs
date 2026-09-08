// Drive the official new-session directory picker, never a filesystem shortcut.
// Usage: node scripts/prepare-deepseek-web-workspace.mjs CDP_PORT
import assert from 'node:assert/strict';
const port = process.argv[2];
assert(/^\d+$/.test(port || ''));
const tabs = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const matches = tabs.filter(t => t.title === 'DeepSeek Harness' && t.type === 'page');
assert.equal(matches.length, 1);
const ws = new WebSocket(matches[0].webSocketDebuggerUrl);
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
let nextId = 0;
const pending = new Map();
ws.onmessage = ({data}) => {
  const m = JSON.parse(data);
  if (!m.id) return;
  const p = pending.get(m.id);
  pending.delete(m.id);
  m.error ? p?.reject(new Error('Browser command failed')) : p?.resolve(m.result);
};
const call = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++nextId;
  pending.set(id, {resolve, reject});
  ws.send(JSON.stringify({id, method, params}));
});
const evaluate = async expression => {
  const result = await call('Runtime.evaluate', {expression, returnByValue: true});
  assert(!result.exceptionDetails, 'Observed picker control unavailable');
  return result.result.value;
};
const waitFor = async expression => {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline) {
    if (await evaluate(expression)) return;
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  throw new Error('Directory picker did not reach the expected state');
};
const timeout = setTimeout(() => {
  console.error('Workspace UI observation timed out'); process.exit(1);
}, 45000);
try {
  if (!await evaluate('!!document.querySelector("[role=dialog]")')) {
    await evaluate('document.querySelector("button[aria-label=\\"Choose workspace\\"]").click()');
  }
  await waitFor('!!document.querySelector("[role=dialog]")');
  if (!await evaluate('!!document.querySelector("input[aria-label=\\"Edit path\\"]")')) {
    await evaluate('document.querySelector("button[aria-label=\\"Edit path\\"]").click()');
  }
  await waitFor('!!document.querySelector("input[aria-label=\\"Edit path\\"]")');
  await evaluate('(()=>{const e=document.querySelector("input[aria-label=\\"Edit path\\"]");e.focus();e.select()})()');
  await call('Input.insertText', {text: '/workspace'});
  await call('Input.dispatchKeyEvent', {type: 'keyDown', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13});
  await call('Input.dispatchKeyEvent', {type: 'keyUp', key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13});
  await waitFor('document.querySelector("[role=dialog]")?.innerText.includes("workspace") && !document.querySelector("input[aria-label=\\"Edit path\\"]")');
  await evaluate('(()=>{const b=[...document.querySelectorAll("[role=dialog] button")].filter(e=>e.innerText==="Open"&&!e.disabled);if(b.length!==1)throw Error("Expected Open");b[0].click()})()');
  await waitFor('!document.querySelector("[role=dialog]")');
  console.log(JSON.stringify({workspace: '/workspace', pickerCompleted: true, conversationVerified: false}));
} finally {
  clearTimeout(timeout);
  ws.close();
}
