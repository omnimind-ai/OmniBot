// Public endpoint advertisement only. Tunnel and ACP lifecycles stay external.
export function publicBridgeUrl(value) {
  if (!String(value ?? '').trim()) return '';
  let url;
  try { url = new URL(String(value).trim()); }
  catch { throw new Error('Public URL must be a ws:// or wss:// Bridge address.'); }
  if (!['ws:', 'wss:'].includes(url.protocol) || !url.hostname ||
      url.username || url.password || url.search || url.hash) {
    throw new Error('Public URL must use ws:// or wss:// without credentials, query or fragment.');
  }
  if (url.pathname === '/') url.pathname = '/codex';
  if (url.pathname !== '/codex') {
    throw new Error('Public URL must use /codex; expose /health and /fs/ on the same origin.');
  }
  return url.href;
}
