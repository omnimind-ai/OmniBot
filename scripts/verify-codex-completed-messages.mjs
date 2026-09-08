// CLI_DIRECTORY must be a disposable npm test installation, not a user's CLI.
// Exercises the exact shipped install patch against real Codex + ACP processes.
import {readFile} from 'node:fs/promises';
import {spawnSync} from 'node:child_process';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';
const [cliDirectory, fixtureDirectory] = process.argv.slice(2);
assert(cliDirectory && fixtureDirectory, 'Usage: node scripts/verify-codex-completed-messages.mjs CLI_DIRECTORY FIXTURE_DIRECTORY');
const installer = await readFile(new URL('../app/src/main/assets/acp/install-codex.sh', import.meta.url), 'utf8');
const patch = installer.split("<<'OOB_CODEX_PATCH'\n")[1]?.split('\nOOB_CODEX_PATCH')[0];
assert(patch, 'Missing shipped Codex completion patch');
const root = resolve(cliDirectory, 'node_modules/@agentclientprotocol/codex-acp');
// Repeat installation to verify it cannot duplicate the projection patch.
for (let i = 0; i < 2; i++) {
  const result = spawnSync(process.execPath, ['-', root], {input: patch, encoding: 'utf8'});
  assert.equal(result.status, 0, result.stderr);
}
for (const scenario of ['completed-text-only', 'partial-text', 'success', 'failure']) {
  const result = spawnSync(process.execPath, [
    new URL('./verify-installed-harness-adapters.mjs', import.meta.url).pathname,
    cliDirectory, fixtureDirectory, 'codex', scenario,
  ], {stdio: 'inherit'});
  assert.equal(result.status, 0, `Codex ${scenario} failed`);
}
