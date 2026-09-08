#!/usr/bin/env node
// Real device tool inventory through the app's installed terminal bootstrap.
// Does not install packages or send a prompt. Missing tools are reported, not hidden.
// Usage: ADB=/path/to/adb node scripts/verify-agent-tools.mjs emulator-5554
// Optional second argument: built-in Agent ID to run its shipped health check.
import {execFileSync} from 'node:child_process';
import {readFileSync} from 'node:fs';
import assert from 'node:assert/strict';
const serial = process.argv[2];
const agentId = process.argv[3];
const catalog = JSON.parse(readFileSync(new URL('../app/src/main/assets/acp/agents.json', import.meta.url), 'utf8'));
const healthCommand = agentId ? catalog.agents.find(a => a.id === agentId)?.runtime?.managedAdapterHealthCommand : null;
assert(!agentId || healthCommand, 'Agent must have a shipped health command');
assert(/^emulator-\d+$/.test(serial || ''), 'Explicit emulator serial required');
const adb = process.env.ADB || 'adb';
const run = args => execFileSync(adb, ['-s', serial, ...args],
  {encoding:'utf8', timeout:15000, stdio:['ignore','pipe','pipe']}).trim();
const apk = run(['shell','pm','path','cn.com.omnimind.bot']);
assert(/^package:\/[^\n]+\/base\.apk$/.test(apk));
const nativeDir = apk.slice(8, -9) + '/lib/arm64';
const prefix = '/data/user/0/cn.com.omnimind.bot';
const quote = s => `'${s.replaceAll("'", "'\\''")}'`;
const probe = healthCommand
  ? `if ( ${healthCommand} ); then echo OOB_HEALTH:passed; else echo OOB_HEALTH:failed; fi`
  : 'export PATH=/root/.local/bin:/root/.npm-global/bin:$PATH; ' +
  'for tool in node npm python3 pip3 uv kimi dsh codex-acp claude-agent-acp opencode; do ' +
  'if command -v "$tool" >/dev/null 2>&1; then echo "OOB_TOOL:$tool:present"; ' +
  'else echo "OOB_TOOL:$tool:missing"; fi; done';
const shell = `set -eu
cd ${prefix}
test -f local/ubuntu/.omnibot-rootfs-ready
probe_dir=$(mktemp -d cache/oob-tool-inventory.XXXXXX)
export PREFIX=${prefix} HOME=/root LINKER=/system/bin/linker64
export LD_LIBRARY_PATH=$PREFIX/local/lib PROOT_LOADER=${nativeDir}/libproot-loader.so
export PROOT_TMP_DIR=$PREFIX/$probe_dir TMPDIR=$PREFIX/tmp
export OMNIBOT_TERMINAL_DISTRIBUTION=ubuntu OMNIBOT_HEADLESS=1
exec /system/bin/sh "$PREFIX/local/bin/init-host" /bin/sh -lc ${quote(probe)}
`;
try {
  const output = execFileSync(adb, ['-s', serial, 'shell', 'run-as',
    'cn.com.omnimind.bot', 'sh'], {input:shell, encoding:'utf8', timeout:60000,
    stdio:['pipe','pipe','pipe']});
  if (agentId) {
    const health = output.match(/^OOB_HEALTH:(passed|failed)$/m)?.[1];
    assert(health, 'Incomplete health response');
    console.log(JSON.stringify({serial, agentId, health, conversationAcceptance: 'not tested'}));
    process.exitCode = health === 'passed' ? 0 : 1;
  } else {
  const statuses = [...output.matchAll(/^OOB_TOOL:([^:]+):(present|missing)$/gm)]
    .map(([,tool,status]) => ({tool,status}));
  assert.equal(statuses.length, 10, 'Incomplete inventory response');
  console.log(JSON.stringify({serial, statuses, conversationAcceptance:'not tested'}));
  if (statuses.some(s => s.status === 'missing')) process.exitCode = 1;
  }
} catch {
  console.error('Device inventory failed; no complete tool report.');
  process.exitCode = 1;
}
