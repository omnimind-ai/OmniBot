#!/usr/bin/env node
// Read-only routing evidence from native desktop diagnostics. This does not
// control the app, send a prompt, or prove rendered-message correctness.
import {readFile} from 'node:fs/promises';

const [logPath, sessionId, expectedHost] = process.argv.slice(2);
if (!logPath || !sessionId || !expectedHost) {
  throw Error('Usage: verify-native-shared-route.mjs LOG SESSION_ID EXPECTED_HOST');
}
const lines = (await readFile(logPath, 'utf8')).split('\n');
const admission = lines.filter(line =>
  line.includes('maybe_resume_started ') &&
  line.includes(`conversationId=${sessionId} `)).at(-1);
const host = admission?.match(/\bhostId=([^\s]+)/)?.[1];
const result = host === expectedHost ? 'PASS' : 'FAIL';
console.log(JSON.stringify({result, observedHost: host ?? null, expectedHost,
  observedAt: admission?.split(' ')[0] ?? null,
  scope: 'Latest logged native GUI session admission route only; not message rendering or end-to-end synchronization'}));
if (result !== 'PASS') process.exitCode = 1;
