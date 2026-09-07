// Observe an explicit local test tab navigation without logging URL queries,
// Cookie values, headers, request bodies, or private page text.
// Usage: node scripts/verify-agent-web-navigation.mjs CDP_PORT PAGE_PORT
import assert from 'node:assert/strict';
const [port, pagePort] = process.argv.slice(2);
assert(/^\d+$/.test(port || '') && /^\d+$/.test(pagePort || ''));
const tabs = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const matches = tabs.filter(tab => tab.type === 'page' &&
  new URL(tab.url).hostname === '127.0.0.1' && new URL(tab.url).port === pagePort);
assert(matches.length > 0, 'No observed local test tab');
const tab = matches[0];
const ws = new WebSocket(tab.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
let nextId = 0;
const pending = new Map();
const results = [];
const cookieBlocks = [];
ws.onmessage = ({data}) => {
  const m = JSON.parse(data);
  if (m.id) {
    const item = pending.get(m.id);
    pending.delete(m.id);
    m.error ? item?.reject(new Error('CDP command failed')) : item?.resolve(m.result);
  } else if (m.method === 'Network.responseReceived') {
    const u = new URL(m.params.response.url);
    if (u.hostname === '127.0.0.1' && u.port === pagePort) {
      results.push({path: u.pathname, status: m.params.response.status});
    }
  } else if (m.method === 'Network.requestWillBeSentExtraInfo') {
    for (const cookie of m.params.associatedCookies || []) {
      cookieBlocks.push(...cookie.blockedReasons);
    }
  }
};
const call = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++nextId;
  pending.set(id, {resolve, reject});
  ws.send(JSON.stringify({id, method, params}));
});
try {
  await call('Network.enable');
  await call('Page.enable');
  await call('Page.navigate', {url: new URL(tab.url).origin + '/'});
  await new Promise(resolve => setTimeout(resolve, 10000));
  const {result} = await call('Runtime.evaluate', {expression:
    'JSON.stringify({ready:document.readyState,controls:document.querySelectorAll("button,input,textarea,[contenteditable=true]").length,unauthorized:document.body?.innerText.includes("authentication required")})',
    returnByValue: true});
  const page = JSON.parse(result.value);
  console.log(JSON.stringify({results, cookieBlocks, page}));
  assert(results.some(r => r.path === '/' && r.status === 200), 'Index not authorized');
  assert(page.controls > 0 && !page.unauthorized, 'No interactive authorized page');
} finally {
  ws.close();
}
