import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, mkdtempSync, writeFileSync, mkdirSync, rmSync, symlinkSync, readlinkSync, existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {spawnSync} from 'node:child_process';
const script = readFileSync('app/src/main/assets/acp/install/opencode.sh', 'utf8');
for (const existingV2 of [false, true]) for (const alpine of [false, true]) for (const failure of [null, 'loader', 'stale-version']) {
  test(`${alpine ? 'Alpine' : 'Ubuntu'} ${existingV2 ? 'V2 update' : 'V1 migration'}: ${failure ?? 'validated install and config backup'}`, () => {
    const root = mkdtempSync(join(tmpdir(), 'oob-opencode-'));
    try {
      const prefix = join(root, 'npm');
      const fakebin = join(root, 'bin');
      mkdirSync(fakebin);
      const platform = alpine ? '@opencode/cli-linux-arm64-musl' : '@opencode/cli-linux-arm64';
      const binary = join(prefix, 'lib/node_modules', platform, 'bin/opencode');
      mkdirSync(join(prefix, 'bin'), {recursive: true});
      mkdirSync(join(binary, '..'), {recursive: true});
      writeFileSync(binary, `#!/bin/sh\nprintf 'opencode v${failure === 'stale-version' ? '2.0.31' : '2.0.32'}\\n'\nexit ${failure === 'loader' ? 127 : 0}\n`, {mode: 0o755});
      if (!existingV2) mkdirSync(join(prefix, 'lib/node_modules/opencode-ai'), {recursive: true});
      const oldBinary = existingV2 ? join(prefix, 'lib/node_modules/@opencode/cli/bin/opencode.exe') : join(root, 'opencode-v1');
      mkdirSync(join(oldBinary, '..'), {recursive: true});
      const oldVersion = existingV2 ? 'opencode v2.0.31' : '1.18.29';
      writeFileSync(oldBinary, `#!/bin/sh\nprintf '${oldVersion}\\n'\n`, {mode: 0o755});
      const link = join(prefix, 'bin/opencode');
      symlinkSync(oldBinary, link);
      const configDirectory = join(root, '.config/opencode');
      mkdirSync(configDirectory, {recursive: true});
      const config = join(configDirectory, 'opencode.json');
      writeFileSync(config, '{"provider":{"custom":{}},"permission":"ask"}');
      const log = join(root, 'calls');
      writeFileSync(join(fakebin, 'npm'), `#!/bin/sh
printf '%s\\n' "$*" >> '${log}'
if [ "$1" = uninstall ]; then
  rm -f '${link}'
  rmdir '${prefix}/lib/node_modules/opencode-ai'
else
  previous=''
  for arg in "$@"; do
    if [ "$previous" = --prefix ]; then target="$arg"; fi
    previous="$arg"
  done
  mkdir -p "$target/lib/node_modules/@opencode/cli/bin" "$target/lib/node_modules/${platform}/bin"
  cp '${binary}' "$target/lib/node_modules/${platform}/bin/opencode"
fi
`, {mode: 0o755});
      writeFileSync(join(fakebin, 'node'), '#!/bin/sh\nprintf "2.0.32\\n"\n', {mode: 0o755});
      const marker = join(root, 'alpine-release');
      if (alpine) writeFileSync(marker, '3.21');
      const run = () => spawnSync('/bin/sh', ['-c', script
        .replaceAll('/root/.npm-global', prefix)
        .replaceAll('/root/.config', join(root, '.config'))
        .replaceAll('/etc/alpine-release', marker)], {
          env: {...process.env, PATH: `${fakebin}:${process.env.PATH}`}, encoding: 'utf8',
        });
      const result = run();
      assert.equal(result.status, failure === 'loader' ? 127 : failure ? 1 : 0, result.stderr);
      const calls = readFileSync(log, 'utf8');
      assert(calls.includes('@opencode/cli@latest'));
      assert(calls.includes(`${platform}@2.0.32`));
      assert(calls.includes('--ignore-scripts --no-bin-links'));
      if (failure) {
        assert.equal(readlinkSync(link), oldBinary, 'A failed V2 validation must leave the previous executable');
        assert(!calls.includes('uninstall'));
        assert.equal(spawnSync(link, ['--version'], {encoding: 'utf8'}).stdout.trim(), oldVersion);
        assert(!existsSync(`${config}.pre-v2.bak`));
      } else {
        assert.equal(calls.includes('uninstall -g'), !existingV2);
        assert.equal(readlinkSync(link), join(prefix, 'lib/node_modules/@opencode/cli/bin/opencode.exe'));
        assert.equal(readFileSync(`${config}.pre-v2.bak`, 'utf8'), readFileSync(config, 'utf8'));
        writeFileSync(config, '{"providers":{"new":{}}}');
        assert.equal(run().status, 0);
        assert.equal(readFileSync(`${config}.pre-v2.bak`, 'utf8'), '{"provider":{"custom":{}},"permission":"ask"}');
      }
    } finally {
      rmSync(root, {recursive: true, force: true});
    }
  });
}
