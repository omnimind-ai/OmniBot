import { mkdtemp, rm } from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';

// Local acceptance fixture only: no production token, provider or model call.
const here = path.dirname(fileURLToPath(import.meta.url));
const sourceRoot = process.env.NA_SOURCE_ROOT || path.resolve(here, '../../codex');
const { createNa } = await import(pathToFileURL(path.join(sourceRoot, 'na/server/main.mjs')));
const dataDir = await mkdtemp(path.join(os.tmpdir(), 'na-app-contract-'));
const app = await createNa({ dataDir,
  token: 'na-app-contract-token-at-least-24-characters',
  runner: async ({ text, onEvent, signal }) => {
    if (text === 'contract-cancel') {
      await new Promise(resolve => signal.addEventListener('abort', resolve, { once: true }));
      throw new Error('cancelled');
    }
    await onEvent({ type: 'thread.started', thread_id: 'app-contract-thread' });
    await onEvent({ type: 'item.updated', item: { id: 'reply', type: 'agent_message', text: 'Contract draft' } });
    await onEvent({ type: 'item.completed', item: { id: 'reply', type: 'agent_message', text: 'Contract complete' } });
  },
});
const { url } = await app.listen(0);
console.log(`NA_CONTRACT_URL=${url}`);
let closing = false;
const close = async () => {
  if (closing) return;
  closing = true;
  await app.close();
  await rm(dataDir, { recursive: true, force: true });
  process.exit(0);
};
process.on('SIGINT', close);
process.on('SIGTERM', close);
const fixtureDurationMs = Number(process.env.NA_CONTRACT_DURATION_MS || 120_000);
if (!Number.isFinite(fixtureDurationMs) || fixtureDurationMs < 1000) throw new Error('Invalid NA_CONTRACT_DURATION_MS');
setTimeout(close, fixtureDurationMs).unref();
