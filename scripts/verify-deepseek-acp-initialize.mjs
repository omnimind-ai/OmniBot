// Probe the installed official DSH ACP profile, without a model request or credentials.
// Usage: ADB=/path/to/adb node scripts/verify-deepseek-acp-initialize.mjs emulator-N [--managed-patch]
import {execFileSync, spawn} from 'node:child_process';
import assert from 'node:assert/strict';
const serial = process.argv[2];
assert(process.argv.slice(3).every(arg => ['--managed-patch', '--disable-link2symlink', '--filesystem-compat', '--app-meta', '--app-capabilities', '--shared-test-key'].includes(arg)), 'Unknown probe option');
const managedPatch = process.argv.includes('--managed-patch');
const disableLink2symlink = process.argv.includes('--disable-link2symlink');
const filesystemCompat = process.argv.includes('--filesystem-compat');
const appMeta = process.argv.includes('--app-meta');
const appCapabilities = process.argv.includes('--app-capabilities');
const sharedTestKey = process.argv.includes('--shared-test-key');
const testKey = sharedTestKey ? process.env.LLMTHU_API_KEY : '';
assert(!sharedTestKey || (testKey && !/[\r\n]/.test(testKey)),
  'Shared test key must exist in the environment and contain no newline');
assert(/^emulator-\d+$/.test(serial || ''), 'Explicit emulator required');
const adb = process.env.ADB || 'adb';
const pkg = 'cn.com.omnimind.bot';
const apk = execFileSync(adb, ['-s', serial, 'shell', 'pm', 'path', pkg],
  {encoding: 'utf8', timeout: 15000}).trim();
assert(/^package:\/[^\n]+\/base\.apk$/.test(apk));
const nativeDir = apk.slice(8, -9) + '/lib/arm64';
const quote = s => `'${s.replaceAll("'", "'\\''")}'`;
const prefix = `/data/user/0/${pkg}`;
const command = `set -eu
${sharedTestKey ? 'IFS= read -r OMNIBOT_DSH_API_KEY; export OMNIBOT_DSH_API_KEY' : ''}
cd ${prefix}
test -f local/ubuntu/.omnibot-rootfs-ready
probe_dir=$(mktemp -d cache/oob-dsh-initialize.XXXXXX)
export PREFIX=${prefix} HOME=/root LINKER=/system/bin/linker64
export LD_LIBRARY_PATH=$PREFIX/local/lib PROOT_LOADER=${nativeDir}/libproot-loader.so
export PROOT_TMP_DIR=$PREFIX/$probe_dir TMPDIR=$PREFIX/tmp
export OMNIBOT_TERMINAL_DISTRIBUTION=ubuntu OMNIBOT_HEADLESS=1
export OMNIBOT_DISABLE_PROOT_LINK2SYMLINK=${disableLink2symlink ? '1' : '0'}
${filesystemCompat ? 'export NODE_OPTIONS="--require /root/.omnibot/acp-fs-compat.cjs"' : ''}
exec /system/bin/sh "$PREFIX/local/bin/init-host" /bin/sh -lc ${quote(
  'export PATH=/root/.npm-global/bin:$PATH; export DSH_HOME=/root/.dsh/omnibot-acp; ' +
  'cd /workspace; exec node --expose-internals /root/.npm-global/lib/node_modules/@deepseek-ai/dsh/lib/bin.js --profile acp' +
  (managedPatch ? ' --patch /root/.dsh/omnibot-acp/omnibot-dispatch.patch.yml' : ''))}
`;
const child = spawn(adb, ['-s', serial, 'shell', '-T', 'run-as', pkg, 'sh', '-c', quote(command)],
  {stdio: ['pipe', 'pipe', 'pipe']});
const startedAt = Date.now();
let pending = '';
let succeeded = false;
let stderr = '';
const deadline = setTimeout(() => {
  console.error('Official DSH initialize did not respond within the 90-second test observation window');
  process.exitCode = 1;
  child.kill();
}, 90000);
child.stderr.on('data', chunk => { stderr += chunk; });
child.stdout.on('data', chunk => {
  pending += chunk;
  const lines = pending.split('\n');
  pending = lines.pop();
  for (const line of lines) {
    let message;
    try { message = JSON.parse(line); } catch { continue; }
    if (typeof message.method === 'string') {
      console.log(JSON.stringify({agentMethod: message.method,
        request: message.id !== undefined, elapsedMs: Date.now() - startedAt}));
    }
    if (message.id !== 1) continue;
    succeeded = message.result?.protocolVersion === 1;
    console.log(JSON.stringify({serial, officialDshInitialize: succeeded,
      managedPatch,
      disableLink2symlink,
      filesystemCompat,
      appMeta,
      appCapabilities,
      sharedTestKey,
      elapsedMs: Date.now() - startedAt,
      protocolVersion: message.result?.protocolVersion, errorCode: message.error?.code}));
    child.stdin.end();
  }
});
child.on('error', () => { clearTimeout(deadline); process.exitCode = 1; });
child.on('close', code => {
  clearTimeout(deadline);
  if (!succeeded) {
    // Restrict diagnostics to known error labels; never dump config or stderr.
    console.error(JSON.stringify({exitCode: code, initialize: 'not verified',
      errorSignals: stderr.match(/ERR_[A-Z_]+|EACCES|ENOENT|Cannot find module|SyntaxError/g) || []}));
    process.exitCode = 1;
  }
});
child.stdin.on('error', () => {});
if (sharedTestKey) child.stdin.write(testKey + '\n');
child.stdin.write(JSON.stringify({jsonrpc: '2.0', id: 1, method: 'initialize', params: {
  protocolVersion: 1, clientInfo: {name: 'oob-regression', version: '1'},
  clientCapabilities: {
    ...(appCapabilities ? {fs: {readTextFile: true, writeTextFile: true},
      terminal: true, plan: {}, elicitation: {form: {}, url: {}}} : {}),
    ...(appMeta ? {_meta: {
    terminal_output: true, 'subagent-transcript': true, dsh: {cordis: {protocol: 0}},
    }} : {}),
  },
}}) + '\n');
