// Read-only preflight for connecting an already-running session. Never resume
// a thread to infer ownership: that can contend with a different app-server.
import WebSocket from 'ws';

export async function inspectSessionOwner({url, sessionId, token, timeoutMs = 10000}) {
  if (!url || !sessionId) throw new Error('Backend URL and session ID are required');
  const socket = new WebSocket(url, {perMessageDeflate: false,
    headers: token ? {Authorization: `Bearer ${token}`} : undefined});
  const pending = new Map();
  let sequence = 0;
  let timer;
  const failure = new Promise((_, reject) => {
    timer = setTimeout(() => reject(Error('Backend ownership check timed out')), timeoutMs);
    socket.on('error', () => reject(Error('Backend ownership endpoint unavailable')));
    socket.on('close', () => reject(Error('Backend closed during ownership check')));
  });
  socket.on('message', raw => {
    let message;
    try { message = JSON.parse(raw); } catch { return; }
    const entry = pending.get(message.id);
    if (!entry) return;
    pending.delete(message.id);
    message.error ? entry.reject(Error('Backend rejected ownership check')) : entry.resolve(message.result);
  });
  function rpc(method, params) {
    return new Promise((resolve, reject) => {
      const id = ++sequence;
      pending.set(id, {resolve, reject});
      socket.send(JSON.stringify({id, method, params}));
    });
  }
  const inspect = async () => {
    await new Promise(resolve => socket.once('open', resolve));
    await rpc('initialize', {clientInfo: {name: 'omnibot-session-owner', version: '1'}});
    socket.send(JSON.stringify({method: 'initialized', params: {}}));
    let cursor;
    const seen = new Set();
    do {
      const result = await rpc('thread/loaded/list', cursor ? {cursor} : {});
      if (!Array.isArray(result.data)) throw Error('Unexpected loaded-thread response');
      if (result.data.includes(sessionId)) {
        const {thread} = await rpc('thread/read', {threadId: sessionId, includeTurns: false});
        if (thread?.id !== sessionId || typeof thread.cwd !== 'string') {
          throw Error('Backend did not return matching session metadata');
        }
        return {sessionId, loadedOnBackend: true, cwd: thread.cwd};
      }
      cursor = result.nextCursor;
      if (cursor && seen.has(cursor)) throw Error('Backend repeated an ownership-list cursor');
      if (cursor) seen.add(cursor);
    } while (cursor);
    return {sessionId, loadedOnBackend: false};
  };
  try {
    return await Promise.race([inspect(), failure]);
  } finally {
    clearTimeout(timer);
    pending.clear();
    socket.terminate();
  }
}
