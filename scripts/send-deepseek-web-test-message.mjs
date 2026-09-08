// Send exactly one test prompt through the official, already prepared Web UI.
// Usage: node scripts/send-deepseek-web-test-message.mjs CDP_PORT MARKER
import assert from 'node:assert/strict';
const [port, marker] = process.argv.slice(2);
assert(/^\d+$/.test(port || '') && /^[A-Z][A-Z0-9_]+$/.test(marker || ''));
const tabs = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const matches = tabs.filter(t => t.type === 'page' && t.title === 'DeepSeek Harness');
assert.equal(matches.length, 1, 'Expected one prepared DeepSeek Web tab');
const ws = new WebSocket(matches[0].webSocketDebuggerUrl);
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
let id = 0;
const pending = new Map();
ws.onmessage = ({data}) => {
  const m = JSON.parse(data);
  if (!m.id) return;
  const item = pending.get(m.id);
  pending.delete(m.id);
  m.error ? item?.reject(new Error('Browser command failed')) : item?.resolve(m.result);
};
const call = (method, params = {}) => new Promise((resolve, reject) => {
  pending.set(++id, {resolve, reject});
  ws.send(JSON.stringify({id, method, params}));
});
const evaluate = async expression => {
  const result = await call('Runtime.evaluate', {expression, returnByValue: true});
  assert(!result.exceptionDetails, 'UI action precondition failed');
  return result.result.value;
};
const timeout = setTimeout(() => { console.error('UI observation timed out'); process.exit(1); }, 30000);
try {
  assert(!await evaluate(`document.body.innerText.includes(${JSON.stringify(marker)})`),
    'Marker already exists; never resend automatically');
  assert(await evaluate('(()=>{const e=[...document.querySelectorAll("[role=textbox][contenteditable=true]")];if(e.length!==1||e[0].innerText.trim())return false;e[0].focus();return true})()'),
  'Expected one empty prepared composer');
  const prompt = `Reply ${marker}`;
  await call('Input.insertText', {text: prompt});
  assert(await evaluate(`document.querySelector('[role=textbox][contenteditable=true]').innerText === ${JSON.stringify(prompt)}`),
    'Draft mismatch; retained without sending');
  assert(await evaluate('(()=>{const b=document.querySelector("button[aria-label=\\"Send message\\"]");if(!b||b.disabled)return false;b.click();return true})()'),
    'Send button not enabled; draft retained');
  console.log(JSON.stringify({marker, sent: true, replyVerified: false}));
} finally {
  clearTimeout(timeout);
  ws.close();
}
