#!/usr/bin/env node
// Configure an explicitly selected emulator via the existing debug-only receiver.
// Credentials come from the same environment as agent_provider_smoke.mjs, never
// command-line arguments or output. Does not replace/delete unrelated profiles.
// Usage: ADB=/path/to/adb node scripts/configure-agent-test-provider.mjs emulator-5554
import {execFileSync} from 'node:child_process';
import assert from 'node:assert/strict';
import {providerSmokeConfigFromEnvironment} from './agent_provider_smoke.mjs';
const serial = process.argv[2];
assert(/^emulator-\d+$/.test(serial || ''), 'Explicit emulator serial required');
const {apiKey, baseUrl, model} = providerSmokeConfigFromEnvironment();
assert(apiKey && baseUrl && model, 'Test Provider environment is incomplete');
const adb = process.env.ADB || 'adb';
const profileId = 'oob-emulator-regression';
const quote = s => `'${s.replaceAll("'", "'\\''")}'`;
const extras = {operation: 'configure', profileId, name: 'Emulator regression',
  baseUrl, apiKey, modelId: model};
const command = 'am broadcast -n cn.com.omnimind.bot/.debug.DebugModelProviderConfigReceiver ' +
  '-a cn.com.omnimind.bot.debug.CONFIGURE_MODEL_PROVIDER ' +
  Object.entries(extras).map(([key,value]) => `--es ${key} ${quote(value)}`).join(' ');
try {
  execFileSync(adb, ['-s', serial, 'shell', 'sh'], {input: command + '\n',
    encoding: 'utf8', timeout: 30000, stdio: ['pipe', 'pipe', 'pipe']});
  const result = JSON.parse(execFileSync(adb, ['-s', serial, 'shell', 'run-as',
    'cn.com.omnimind.bot', 'cat', 'files/debug-model-provider-config-result.json'],
    {encoding: 'utf8', timeout: 15000, stdio: ['ignore', 'pipe', 'pipe']}));
  assert(result.success && result.configuredProfileId === profileId &&
    result.configuredModelId === model, 'Receiver did not confirm the requested configuration');
  console.log(JSON.stringify({passed: true, serial, profileId, model,
    scope: 'Provider configuration only; no conversation acceptance'}));
} catch {
  // Error objects from execFileSync can contain stdin. Never print them.
  console.error('Test Provider configuration failed; inspect the debug receiver without exposing credentials.');
  process.exitCode = 1;
}
