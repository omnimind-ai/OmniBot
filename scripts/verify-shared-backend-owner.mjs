#!/usr/bin/env node
import {readFile} from 'node:fs/promises';
import {inspectSessionOwner} from '../deploy/codex-relay/shared-session-owner.mjs';

const [sessionId] = process.argv.slice(2);
try {
  const token = process.env.OOB_SHARED_CODEX_TOKEN_FILE
    ? (await readFile(process.env.OOB_SHARED_CODEX_TOKEN_FILE, 'utf8')).trim() : undefined;
  const result = await inspectSessionOwner({url: process.env.OOB_SHARED_CODEX_URL, sessionId, token});
  console.log(JSON.stringify({...result,
    scope: 'Loaded membership on the configured backend only; no resume, prompt or history read'}));
  if (!result.loadedOnBackend) process.exitCode = 2;
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
